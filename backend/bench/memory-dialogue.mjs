import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { WebSocket } from 'ws';
import { sessionConfig as baselineConfig } from './baseline-protocol.js';
import { sessionConfig as productionConfig } from '../lib/protocol.js';
import { BridgeSession } from '../lib/session.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_URL = 'wss://api.boson.ai/v1/realtime/';
export const CASES_PATH = resolve(HERE, 'memory-dialogue-cases.json');
const BASELINE_PATH = resolve(HERE, 'baseline-protocol.js');
const PRODUCTION_PATH = resolve(HERE, '../lib/protocol.js');
const SESSION_PATH = resolve(HERE, '../lib/session.js');
const MEMORY_CONTEXT_PATH = resolve(HERE, '../lib/memory-context.js');

// Evaluation-only indicators. These never rewrite, suppress, or retry model output.
export const PROCESS_INDICATORS = [
  /\b(?:look(?:ing)?(?:\s+up)?|check(?:ing)?|search(?:ing)?|retriev(?:e|ing)|access(?:ing)?|consult(?:ing)?|sav(?:e|ing)|stor(?:e|ing))\b.{0,55}\b(?:memor(?:y|ies)|records?|database|notes?|logs?)\b/iu,
  /\b(?:memory tool|recall_memories|remember_conversation|database|storage|tool call)\b/iu,
  /\b(?:let me|I will|I'll|I'm|I am)\b.{0,35}\b(?:retrieve|search|look up|check my records)\b/iu,
];

export async function loadCases() {
  const document = JSON.parse(await readFile(CASES_PATH, 'utf8'));
  if (document.schemaVersion !== 1 || !Array.isArray(document.cases)) throw new Error('Invalid benchmark fixtures');
  const ids = new Set();
  for (const fixture of document.cases) {
    if (ids.has(fixture.id) || !fixture.id || !fixture.question || fixture.memories.length > 8) {
      throw new Error('Invalid or duplicate benchmark case');
    }
    ids.add(fixture.id);
    for (const pattern of fixture.rubric.requiredAny.flat().concat(fixture.rubric.forbidden)) new RegExp(pattern, 'iu');
  }
  return document;
}

export function buildContext(fixture, asOf, arm) {
  const memories = fixture.memories.map((memory, i) => ({
    storyId: memory.id,
    summary: memory.summary,
    toldByPlayer: 'Alex',
    originVillager: memory.originVillager ?? 'Rowan',
    heardFrom: memory.heardFrom ?? 'Rowan',
    kind: memory.kind ?? 'player_told_me',
    private: memory.private ?? false,
    interest: memory.interest ?? 40,
    whyInteresting: 'A previously shared detail retained for this synthetic conversation.',
    createdAt: memory.createdAt ?? asOf - (fixture.memories.length - i) * 3_600_000,
    learnedAt: memory.createdAt ?? asOf - (fixture.memories.length - i) * 3_600_000,
  }));
  const context = {
    name: 'Rowan', profession: 'librarian', playerName: 'Alex',
    personality: { catalogueVersion: 1, archetypeId: 'rowan', styleId: 'measured', familiarity: 4 },
    memories, heardRumours: [],
  };
  if (arm === 'prepared') context.memoryRecall = { status: 'ready', asOf };
  return context;
}

export function evaluateTranscript(fixture, result) {
  const transcript = result.transcript.replace(/[’‘]/gu, "'").replace(/\s+/gu, ' ').trim();
  const missingRequired = fixture.rubric.requiredAny.filter(alternatives =>
    !alternatives.some(pattern => new RegExp(pattern, 'iu').test(transcript)));
  const unsupportedMatches = fixture.rubric.forbidden.filter(pattern => new RegExp(pattern, 'iu').test(transcript));
  const processMatches = PROCESS_INDICATORS.flatMap(pattern => {
    const match = transcript.match(pattern);
    return match ? [match[0]] : [];
  });
  return {
    lexicalRubricPass: transcript.length > 0 && missingRequired.length === 0 && unsupportedMatches.length === 0,
    missingRequired, unsupportedMatches,
    processNarration: processMatches.length > 0, processMatches,
    needsHumanReview: true,
  };
}

function simulatedToolResult(call, context) {
  if (call.name === 'recall_memories') return {
    memories: context.memories,
    note: 'Memories are attributed statements, not verified world facts. An empty list means no matching saved knowledge.',
  };
  if (call.name === 'remember_conversation') return {
    status: 'saved', memoryId: `synthetic-${call.callId}`, private: Boolean(call.arguments?.private), gossipEligible: false,
  };
  if (call.name === 'report_treatment') return { status: 'recorded', relationship: 0, pricePercent: 100 };
  return { error: 'This synthetic conversation has no available trade or game action.' };
}

/**
 * One real provider session. Inputs are synthetic text; outputs use production audio modality.
 * Full identical evidence is already available in both arms. Baseline lookup returns that same
 * evidence, deliberately isolating dialogue architecture from retrieval recall or latency.
 * Injectable factory/socket make a later production-PTT adapter possible without changing cases.
 */
export function runDialogueCase({ fixture, asOf, arm, apiKey, upstreamUrl = DEFAULT_URL,
  configFactory = arm === 'baseline' ? baselineConfig : productionConfig,
  voiceOverrides = {}, connect = (url, options) => new WebSocket(url, options), timeoutMs = 45_000 }) {
  const context = buildContext(fixture, asOf, arm);
  const config = configFactory(context, 'ptt', voiceOverrides);
  const start = performance.now();
  return new Promise(resolveResult => {
    let socket, settled = false, started = false, activeResponse = null;
    let audioBytes = 0, firstAudioAt = null, responseCount = 0;
    const calls = [], seenCalls = new Set(), responses = [], transcriptItems = new Map();
    // Use the actual production delivery method, retaining object identity across
    // tool continuations so it delivers evidence once for this prepared turn.
    const delivery = { preparedMemory: { memories: context.memories, status: 'ready', asOf }, sendProvider: send,
      deliverCompanionLines: BridgeSession.prototype.deliverCompanionLines };
    const requestResponse = () => arm === 'prepared'
      ? BridgeSession.prototype.createConversationResponse.call(delivery)
      : send({ type: 'response.create' });
    const timeout = setTimeout(() => finish('timeout', 'Provider case exceeded timeout'), timeoutMs);

    function finish(status, error) {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (socket?.readyState === WebSocket.OPEN) socket.close();
      else if (socket && socket.readyState !== WebSocket.CLOSED) socket.terminate();
      const transcript = [...transcriptItems.values()].map(item => item.text).filter(Boolean).join('\n');
      const result = {
        caseId: fixture.id, category: fixture.category, arm, status,
        ...(error ? { error } : {}), question: fixture.question, transcript,
        transcriptItems: [...transcriptItems.values()], toolCalls: calls, responses,
        elapsedMs: Math.round(performance.now() - start),
        firstAudioMs: firstAudioAt === null ? null : Math.round(firstAudioAt - start), audioBytes,
        model: config.session.model, voice: config.session.audio.output.voice,
        outputModalities: config.session.output_modalities,
        temperature: config.session.temperature ?? 'provider default; identical arms',
      };
      result.evaluation = status === 'completed' ? evaluateTranscript(fixture, result) : null;
      resolveResult(result);
    }
    function send(event) {
      if (socket.readyState !== WebSocket.OPEN) return false;
      socket.send(JSON.stringify(event));
      return true;
    }
    function recordCall(item, responseId) {
      const callId = item.call_id;
      if (!callId || seenCalls.has(callId)) return;
      seenCalls.add(callId);
      let args;
      try { args = JSON.parse(item.arguments); } catch { args = null; }
      calls.push({ callId, name: item.name, arguments: args, responseId, outputSent: false });
    }
    try {
      socket = connect(upstreamUrl, { headers: { Authorization: `Bearer ${apiKey}` },
        maxPayload: 2 * 1024 * 1024, handshakeTimeout: 15_000 });
    } catch {
      finish('error', 'Provider connection could not be created');
      return;
    }
    socket.on('open', () => send(config));
    socket.on('message', raw => {
      if (settled) return;
      let event;
      try { event = JSON.parse(raw.toString()); } catch { return finish('error', 'Invalid provider JSON'); }
      // Higgs acknowledges the first session.update with session.created, and later
      // updates with session.updated. Require the actual requested instructions.
      if (['session.created', 'session.updated'].includes(event.type) && !started) {
        if (event.session?.instructions !== config.session.instructions) return;
        started = true;
        send({ type: 'conversation.item.create', item: { type: 'message', role: 'user',
          content: [{ type: 'input_text', text: fixture.question }] } });
        requestResponse();
      } else if (event.type === 'response.created') {
        activeResponse = event.response?.id;
        responseCount++;
      } else if (['response.output_audio.delta', 'response.audio.delta'].includes(event.type)) {
        if (firstAudioAt === null) firstAudioAt = performance.now();
        audioBytes += Buffer.from(event.delta ?? '', 'base64').length;
      } else if (['response.output_audio_transcript.delta', 'response.output_audio_transcript.done',
        'response.audio_transcript.delta', 'response.audio_transcript.done'].includes(event.type)) {
        const key = `${event.response_id ?? activeResponse}:${event.item_id ?? event.output_index ?? 0}:${event.content_index ?? 0}`;
        const previous = transcriptItems.get(key)?.text ?? '';
        transcriptItems.set(key, { responseId: event.response_id ?? activeResponse,
          text: event.type.endsWith('.done') ? String(event.transcript ?? previous) : previous + String(event.delta ?? '') });
      } else if (event.type === 'response.function_call_arguments.done') {
        recordCall(event, event.response_id ?? activeResponse);
      } else if (event.type === 'response.done') {
        const response = event.response ?? {};
        for (const item of response.output ?? []) {
          if (item.type === 'function_call') recordCall(item, response.id);
        }
        responses.push({ id: response.id, status: response.status, usage: response.usage ?? null });
        if (response.status !== 'completed') return finish('error', `Provider response ${String(response.status)}`);
        const pending = calls.filter(call => !call.outputSent);
        if (!pending.length) return finish('completed');
        if (responseCount >= 4 || calls.length > 8) return finish('error', 'Synthetic tool continuation limit reached');
        for (const call of pending) {
          const output = simulatedToolResult(call, context);
          call.outputSent = true;
          call.result = output;
          send({ type: 'conversation.item.create', item: { type: 'function_call_output',
            call_id: call.callId, output: JSON.stringify(output) } });
        }
        requestResponse();
      } else if (event.type === 'error') {
        // Provider messages can echo input/header data: preserve only bounded type/code/param.
        const safe = value => String(value ?? '').replace(/[^a-zA-Z0-9_.-]/gu, '_').slice(0, 100);
        finish('error', `Provider error ${safe(event.error?.type)} ${safe(event.error?.code)} ${safe(event.error?.param)}`);
      }
    });
    socket.on('error', () => finish('error', 'Provider websocket connection failed'));
    socket.on('close', code => { if (!settled) finish('error', `Provider websocket closed (${code})`); });
  });
}

export function summarize(results) {
  const summarizeGroup = group => {
    const completed = group.filter(result => result.status === 'completed');
    return { attempted: group.length, completed: completed.length, failed: group.length - completed.length,
      lexicalRubricPasses: completed.filter(result => result.evaluation.lexicalRubricPass).length,
      processNarrationCases: completed.filter(result => result.evaluation.processNarration).length,
      recallToolCalls: completed.reduce((sum, result) => sum + result.toolCalls.filter(call => call.name === 'recall_memories').length, 0),
      meanElapsedMs: completed.length ? Math.round(completed.reduce((sum, result) => sum + result.elapsedMs, 0) / completed.length) : null,
    };
  };
  return Object.fromEntries(['baseline', 'prepared'].map(arm => {
    const armResults = results.filter(result => result.arm === arm);
    return [arm, { all: summarizeGroup(armResults),
      ordinary: summarizeGroup(armResults.filter(result => result.category !== 'adversarial_process_request')),
      explicitLookupRequest: summarizeGroup(armResults.filter(result => result.category === 'adversarial_process_request')) }];
  }));
}

export async function runBenchmark({ fixtures, apiKey, upstreamUrl, voiceOverrides = {}, repeats = 1,
  arms = ['baseline', 'prepared'], onResult = () => {} }) {
  if (!apiKey) throw new Error('BOSON_API_KEY is not configured');
  if (!Number.isInteger(repeats) || repeats < 1 || repeats > 3) throw new Error('Repeats must be between 1 and 3');
  const results = [];
  for (let repetition = 0; repetition < repeats; repetition++) {
    for (const [i, fixture] of fixtures.cases.entries()) {
      // Alternate order within pairs so one arm is not always the later provider request.
      const order = (i + repetition) % 2 ? [...arms].reverse() : arms;
      for (const arm of order) {
        const result = await runDialogueCase({ fixture, asOf: fixtures.asOf, arm, apiKey, upstreamUrl, voiceOverrides });
        result.repetition = repetition + 1;
        results.push(result);
        await onResult(result, results);
      }
    }
  }
  return { results, summary: summarize(results) };
}

async function main() {
  const args = process.argv.slice(2);
  const option = name => args.find(value => value.startsWith(`${name}=`))?.slice(name.length + 1);
  const fixtures = await loadCases();
  const ids = option('--case')?.split(',');
  if (ids) fixtures.cases = fixtures.cases.filter(fixture => ids.includes(fixture.id));
  if (!fixtures.cases.length || ids?.some(id => !fixtures.cases.some(fixture => fixture.id === id))) throw new Error('Unknown --case id');
  const arms = option('--arms')?.split(',') ?? ['baseline', 'prepared'];
  if (arms.some(arm => !['baseline', 'prepared'].includes(arm)) || new Set(arms).size !== arms.length) throw new Error('Invalid --arms');
  const repeats = Number(option('--repeats') ?? 1);
  const hashes = await Promise.all([CASES_PATH, BASELINE_PATH, PRODUCTION_PATH, SESSION_PATH, MEMORY_CONTEXT_PATH,
    fileURLToPath(import.meta.url)].map(async path =>
    createHash('sha256').update(await readFile(path)).digest('hex')));
  const report = {
    schemaVersion: 1, benchmark: fixtures.name, createdAt: new Date().toISOString(),
    fixtureSha256: hashes[0], baselineProtocolSha256: hashes[1], productionProtocolSha256: hashes[2],
    productionSessionSha256: hashes[3], productionMemoryContextSha256: hashes[4], harnessSha256: hashes[5],
    baseline: 'Frozen pre-change protocol including user edits captured 2026-10-24.',
    methodology: 'Identical initial evidence (at most eight memories), model, personality, voice and audio modality in both arms. Synthetic text input; default provider temperature in both. Baseline tools return the same full evidence pool. Prepared arm uses production PTT config and actual BridgeSession.createConversationResponse to deliver the same evidence as a labeled context item before a bare response.create, maintaining identity across tool continuations. Each pair alternates order. No censorship, retry-on-content, or runtime phrase rules.',
    limitations: 'Small synthetic architecture experiment, not official LongMemEval/LoCoMo results. Lexical rubric matches and process indicators need human transcript review. Exercises production context delivery but does not measure actual retrieval, microphone/STT, server persistence, PTT transcription/recall coordination, or in-game audio behavior. Failed requests are reported separately. First-audio latency includes connection setup.',
    sources: ['https://github.com/xiaowu0162/LongMemEval', 'https://github.com/snap-research/locomo'],
    cases: fixtures.cases.map(({ id, category, question }) => ({ id, category, question })),
    results: [], summary: summarize([]),
  };
  if (!args.includes('--live')) {
    console.log(JSON.stringify({ ...report, dryRun: true, message: 'No network calls made. Pass --live to run the limited provider benchmark.' }, null, 2));
    return;
  }
  try { process.loadEnvFile(resolve(HERE, '../../.env')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const voiceOverrides = { warm: process.env.HIGGS_VOICE_WARM, thoughtful: process.env.HIGGS_VOICE_THOUGHTFUL,
    steady: process.env.HIGGS_VOICE_STEADY };
  const reference = baselineConfig(buildContext(fixtures.cases[0], fixtures.asOf, 'baseline'), 'ptt', voiceOverrides).session;
  const candidate = productionConfig(buildContext(fixtures.cases[0], fixtures.asOf, 'prepared'), 'ptt', voiceOverrides).session;
  if (arms.includes('prepared') && candidate.tools.some(tool => tool.name === 'recall_memories')) {
    throw new Error('Prepared production PTT config still exposes recall_memories; architecture is not ready for this benchmark');
  }
  for (const field of ['model', 'audio', 'output_modalities', 'temperature']) {
    if (JSON.stringify(reference[field]) !== JSON.stringify(candidate[field])) throw new Error(`A/B mismatch in ${field}`);
  }
  const output = resolve(option('--out') ?? resolve(HERE, 'results', `memory-dialogue-${Date.now()}.json`));
  await mkdir(dirname(output), { recursive: true });
  const save = async () => writeFile(output, JSON.stringify(report, null, 2) + '\n');
  await save();
  const run = await runBenchmark({ fixtures, apiKey: process.env.BOSON_API_KEY,
    upstreamUrl: process.env.HIGGS_REALTIME_URL ?? DEFAULT_URL, voiceOverrides, repeats, arms,
    onResult: async (result, all) => {
      report.results = all; report.summary = summarize(all); await save();
      console.log(`${result.arm} ${result.caseId}: ${result.status}`
        + (result.evaluation ? `; rubric=${result.evaluation.lexicalRubricPass}; process=${result.evaluation.processNarration}` : ''));
    } });
  report.results = run.results; report.summary = run.summary; await save();
  console.log(JSON.stringify({ output, summary: report.summary }, null, 2));
  if (run.results.some(result => result.status !== 'completed')) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
