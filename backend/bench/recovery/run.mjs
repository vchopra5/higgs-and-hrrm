import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TYPES, DEPTHS, SCENARIOS, buildFixture, executeTool, evaluate } from './fixtures.mjs';
import { higgsTurn, openRouterTurn } from './providers.mjs';
import { synthesize, waveFromPcm } from './audio.mjs';
import { JUDGE_PROMPT, JUDGE_VERSION, judgeEvidence, judgeTimeline } from './judge.mjs';
export { judgeTimeline } from './judge.mjs';
const HERE = dirname(fileURLToPath(import.meta.url));
const hash = v => createHash('sha256').update(typeof v === 'string' ? v : JSON.stringify(v)).digest('hex');
export const MODELS = { higgs: 'higgs-realtime', openrouter: 'openai/gpt-audio' };

export async function runTrial(fixture, { provider, model, apiKey, audio, upstreamUrl, audioDir, turn = provider === 'higgs' ? higgsTurn : openRouterTurn }) {
  const messages = structuredClone(fixture.messages), finalState = structuredClone(fixture.state);
  const r = { fixtureId: fixture.id, type: fixture.type, depth: fixture.depth, scenario: fixture.scenario, arm: fixture.arm,
    factPosition: fixture.factPosition, provider, model, inputModality: audio ? 'audio' : 'text', outputModality: 'audio',
    historyModality: 'text', audioHash: audio?.sha256 ?? null, audioDurationMs: audio?.durationMs ?? null,
    transcript: '', calls: [], rounds: [], finalState, status: 'running', evaluation: null };
  const start = performance.now(), audioIndex = messages.length - 1;
  try {
    for (let round = 0; round < 5; round++) {
      const response = await turn({ apiKey, model, instructions: fixture.instructions, tools: fixture.tools,
        messages, audio, audioIndex, upstreamUrl, captureAudio: Boolean(audioDir) });
      if (response.audioPcm) {
        const pcm = response.audioPcm; delete response.audioPcm;
        if (pcm.length) {
          await mkdir(audioDir, { recursive: true });
          const wav = waveFromPcm(pcm);
          const digest = createHash('sha256').update(wav).digest('hex'), name = digest + '.wav';
          await writeFile(resolve(audioDir, name), wav);
          response.audioArtifact = { path: name, sha256: digest, format: 'WAV PCM16 mono 24000Hz' };
        }
      }
      r.rounds.push(response); r.transcript += (r.transcript ? '\n' : '') + response.text;
      if (!response.calls.length) {
        r.status = response.text.trim() && response.audioBytes > 0 ? 'completed' : 'empty_or_missing_audio'; break;
      }
      messages.push({ role: 'assistant', content: response.text || null, tool_calls: response.calls });
      const ids = new Set();
      for (const raw of response.calls) {
        if (!raw.id || ids.has(raw.id)) throw new Error('Duplicate or missing tool call ID');
        ids.add(raw.id);
        let args; try { args = JSON.parse(raw.function.arguments); } catch { args = null; }
        const call = { id: raw.id, name: raw.function.name, arguments: args };
        call.result = executeTool(finalState, call); r.calls.push(call);
        messages.push({ role: 'tool', tool_call_id: raw.id, content: JSON.stringify(call.result) });
      }
      if (round === 4) r.status = 'tool_limit';
    }
    if (r.status === 'completed') r.evaluation = evaluate(fixture, r);
  } catch (e) {
    r.status = 'error';
    // Do not persist exception text that could contain request headers, URLs, or credentials.
    r.error = /^(OpenRouter HTTP \d+|OpenRouter (?:incomplete response|stream error)[\w ()-]*|Higgs [\w ()-]+|Duplicate or missing tool call ID)$/.test(e.message)
      ? e.message : 'Provider request or parsing failed';
  }
  r.elapsedMs = Math.round(performance.now() - start);
  return r;
}

export async function judgeTrial(f, r, { apiKey, model, fetchImpl = fetch }) {
  const response = await fetchImpl('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST', signal: AbortSignal.timeout(60000), headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, temperature: 0, max_tokens: 1200, response_format: { type: 'json_object' },
      provider: { allow_fallbacks: false, require_parameters: true },
      messages: [{ role: 'system', content: JUDGE_PROMPT },
      { role: 'user', content: JSON.stringify(judgeEvidence(f, r)) }] }),
  });
  if (!response.ok) throw new Error(`Judge HTTP ${response.status}`);
  const body = await response.json();
  const verdict = JSON.parse(body.choices?.[0]?.message?.content);
  if (typeof verdict.taskFulfillment !== 'boolean' || typeof verdict.recoveryQuality !== 'boolean'
      || !Array.isArray(verdict.criteria) || verdict.criteria.length !== f.rubric.length
      || !verdict.criteria.every(c => typeof c.pass === 'boolean' && typeof c.evidence === 'string')
      || typeof verdict.explanation !== 'string'
      || verdict.recoveryQuality !== verdict.criteria.every(c => c.pass)) throw new Error('Invalid judge verdict');
  return { judgeVersion: JUDGE_VERSION, model, returnedModel: body.model, usage: body.usage ?? null, ...verdict, blindedToModelIdentity: true, humanReviewed: false };
}

export function summarize(results) {
  const groups = {};
  for (const r of results) {
    const key = [r.provider, r.model, r.type, r.depth, r.factPosition, r.arm].join('|');
    const g = groups[key] ??= { provider: r.provider, model: r.model, type: r.type, depth: r.depth, factPosition: r.factPosition,
      arm: r.arm, attempted: 0, completed: 0, errors: 0, mechanicalPasses: 0, judged: 0, taskPasses: 0, recoveryPasses: 0 };
    g.attempted++;
    if (r.status !== 'completed') { g.errors++; continue; }
    g.completed++; if (r.evaluation.mechanicalPass) g.mechanicalPasses++;
    if (r.judge) { g.judged++; if (r.judge.taskFulfillment) g.taskPasses++; if (r.judge.recoveryQuality) g.recoveryPasses++; }
  }
  const pairs = new Map();
  for (const r of results) {
    const key = [r.provider, r.model, r.type, r.depth, r.scenario, r.factPosition, r.repeat].join('|');
    const p = pairs.get(key) ?? {}; p[r.arm] = r; pairs.set(key, p);
  }
  const matched = [];
  for (const [key, p] of pairs) {
    if (p.control?.status !== 'completed' || p.interrupted?.status !== 'completed') continue;
    matched.push({ key, depth: p.control.depth, type: p.control.type,
      mechanicalInterruptionPenalty: Number(p.control.evaluation.mechanicalPass) - Number(p.interrupted.evaluation.mechanicalPass),
      taskInterruptionPenalty: p.control.judge && p.interrupted.judge ? Number(p.control.judge.taskFulfillment) - Number(p.interrupted.judge.taskFulfillment) : null,
      recoveryInterruptionPenalty: p.control.judge && p.interrupted.judge ? Number(p.control.judge.recoveryQuality) - Number(p.interrupted.judge.recoveryQuality) : null });
  }
  return { groups: Object.values(groups).map(g => ({ ...g, mechanicalRate: g.completed ? g.mechanicalPasses / g.completed : null,
    taskRate: g.judged ? g.taskPasses / g.judged : null, recoveryRate: g.judged ? g.recoveryPasses / g.judged : null })), matched,
    note: 'Rates condition on completed/judged trials; errors are separate. No significance claim. Filler arm rubrics differ because control speech is complete. Use matched task penalties for direct comparison.' };
}

export function parseOptions(args) {
  const allowed = new Set(['live', 'resume', 'out', 'providers', 'depths', 'types', 'scenarios', 'repeats', 'input', 'fact-position', 'limit', 'judge-model', 'openrouter-model', 'higgs-model']);
  const o = {};
  for (const a of args) {
    if (!a.startsWith('--')) throw new Error(`Unknown argument ${a}`);
    const i = a.indexOf('='), key = a.slice(2, i < 0 ? undefined : i), value = i < 0 ? true : a.slice(i + 1);
    if (!allowed.has(key) || key in o) throw new Error(`Invalid option ${key}`); o[key] = value;
  }
  const select = (key, defaults, choices) => {
    const values = o[key] === undefined ? defaults : String(o[key]).split(',');
    if (!values.length || new Set(values).size !== values.length || values.some(v => !choices.includes(v))) throw new Error(`Invalid ${key}`);
    return values;
  };
  const repeats = Number(o.repeats ?? 2), limit = Number(o.limit ?? 10000);
  if (!Number.isInteger(repeats) || repeats < 1 || repeats > 5 || !Number.isInteger(limit) || limit < 1) throw new Error('Invalid repeats/limit');
  const input = o.input ?? 'audio', factPosition = o['fact-position'] ?? 'early';
  if (!['audio', 'text'].includes(input) || !['early', 'recent'].includes(factPosition)) throw new Error('Invalid input/fact-position');
  return { live: o.live === true, resume: o.resume === true, out: resolve(o.out ?? resolve(HERE, '../results/recovery-depth.json')),
    providers: select('providers', ['higgs', 'openrouter'], ['higgs', 'openrouter']), depths: select('depths', DEPTHS.map(String), DEPTHS.map(String)).map(Number),
    types: select('types', TYPES, TYPES), scenarios: select('scenarios', SCENARIOS.map(s => s.id), SCENARIOS.map(s => s.id)),
    repeats, limit, input, factPosition, judgeModel: o['judge-model'] ?? null,
    models: { higgs: o['higgs-model'] ?? MODELS.higgs, openrouter: o['openrouter-model'] ?? MODELS.openrouter } };
}

async function main() {
  const options = parseOptions(process.argv.slice(2)), schedule = [];
  for (let repeat = 1; repeat <= options.repeats; repeat++) for (const type of options.types) for (const scenario of options.scenarios)
    for (const depth of options.depths) for (const arm of ((repeat + depth + TYPES.indexOf(type)) % 2 ? ['control', 'interrupted'] : ['interrupted', 'control'])) {
      const fixture = buildFixture({ type, scenario, depth, arm, factPosition: options.factPosition });
      const order = repeat % 2 ? options.providers : [...options.providers].reverse();
      for (const provider of order) schedule.push({ fixture, provider, repeat, model: options.models[provider] });
    }
  const trials = schedule.slice(0, options.limit);
  if (!options.live) { console.log(JSON.stringify({ mode: 'preview', options, totalTrials: schedule.length, selectedTrials: trials.length,
    examples: trials.slice(0, 2).map(t => ({ id: t.fixture.id, provider: t.provider, question: t.fixture.question, rubric: t.fixture.rubric })) }, null, 2)); return; }
  try { process.loadEnvFile(resolve(HERE, '../../../.env')); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  const keys = { higgs: process.env.BOSON_API_KEY, openrouter: process.env.OPENROUTER_API_KEY };
  for (const p of options.providers) if (!keys[p]) throw new Error(`${p === 'higgs' ? 'BOSON_API_KEY' : 'OPENROUTER_API_KEY'} missing`);
  if (options.judgeModel && !keys.openrouter) throw new Error('OPENROUTER_API_KEY missing for judge');
  const hashes = {};
  for (const p of ['fixtures.mjs', 'providers.mjs', 'run.mjs', 'audio.mjs', 'judge.mjs', '../../lib/protocol.js']) hashes[p] = hash(await readFile(resolve(HERE, p), 'utf8'));
  const signature = hash({ options: { ...options, resume: false, out: null }, hashes });
  let report;
  if (options.resume) {
    report = JSON.parse(await readFile(options.out, 'utf8'));
    if (report.signature !== signature) throw new Error('Resume refused: configuration or source hashes changed');
  } else {
    try { await readFile(options.out); throw new Error('Output exists; choose another --out or use --resume'); } catch (e) { if (e.code !== 'ENOENT') throw e; }
    report = { schemaVersion: 1, createdAt: new Date().toISOString(), track: 'IHBench-inspired recorded-prefix recovery; not live barge-in',
      signature, hashes, options, selectedTrials: trials.length, fixtures: [...new Map(trials.map(t => [t.fixture.id, t.fixture])).values()], results: [] };
  }
  await mkdir(dirname(options.out), { recursive: true });
  const save = async () => { report.summary = summarize(report.results); await writeFile(options.out + '.tmp', JSON.stringify(report, null, 2) + '\n'); await rename(options.out + '.tmp', options.out); };
  const audioCache = new Map(), done = new Set(report.results.map(r => r.trialId));
  for (const t of trials) {
    const trialId = `${t.provider}/${t.model}/${t.fixture.id}/${t.repeat}`;
    if (done.has(trialId)) continue;
    let audio;
    if (options.input === 'audio') {
      if (!audioCache.has(t.fixture.question)) audioCache.set(t.fixture.question, await synthesize(t.fixture.question));
      audio = audioCache.get(t.fixture.question);
    }
    const r = await runTrial(t.fixture, { provider: t.provider, model: t.model, apiKey: keys[t.provider], audio, upstreamUrl: process.env.HIGGS_REALTIME_URL });
    r.trialId = trialId; r.repeat = t.repeat;
    if (r.status === 'completed' && options.judgeModel) {
      try { r.judge = await judgeTrial(t.fixture, r, { apiKey: keys.openrouter, model: options.judgeModel }); }
      catch { r.judgeError = 'Judge request or verdict validation failed'; }
    }
    report.results.push(r); await save();
    console.log(JSON.stringify({ trialId, status: r.status, error: r.error, mechanical: r.evaluation?.mechanicalPass, recovery: r.judge?.recoveryQuality }));
    // Preserve failures and stop on access/transport problems instead of spending the full matrix.
    if (r.status === 'error') { process.exitCode = 1; break; }
  }
  console.log(`Saved ${report.results.length}/${trials.length} trials to ${options.out}`);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) main().catch(e => { console.error(e.message); process.exitCode = 1; });
