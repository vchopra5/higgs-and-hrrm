import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { mkdtemp, readFile, rm, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { WebSocket } from 'ws';
import { createBridge } from '../lib/bridge.js';

const run = promisify(execFile);
const delay = ms => new Promise(resolveDelay => setTimeout(resolveDelay, ms));
const question = 'Where did I tell you I hid the star compass?';
const memory = {
  storyId: 'synthetic-ptt-compass',
  summary: 'Alex said the star compass is inside a blue barrel beneath the violet windmill.',
  toldByPlayer: 'Alex', originVillager: 'Rowan', heardFrom: 'Rowan',
  kind: 'player_told_me', private: true, interest: 35,
};

async function until(predicate, timeoutMs = 45_000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('Production PTT probe timed out');
    await delay(20);
  }
}

function pcmFromWave(wave) {
  assert.equal(wave.toString('ascii', 0, 4), 'RIFF');
  assert.equal(wave.toString('ascii', 8, 12), 'WAVE');
  for (let offset = 12; offset + 8 <= wave.length;) {
    const size = wave.readUInt32LE(offset + 4);
    const kind = wave.toString('ascii', offset, offset + 4);
    if (kind === 'fmt ') {
      assert.equal(wave.readUInt16LE(offset + 8), 1, 'PCM format required');
      assert.equal(wave.readUInt16LE(offset + 10), 1, 'Mono audio required');
      assert.equal(wave.readUInt32LE(offset + 12), 24000, '24 kHz audio required');
      assert.equal(wave.readUInt16LE(offset + 22), 16, '16-bit audio required');
    }
    if (kind === 'data') return wave.subarray(offset + 8, offset + 8 + size);
    offset += 8 + size + (size % 2);
  }
  throw new Error('No PCM data in synthetic WAV');
}

export async function runProductionMemoryProbe({ apiKey, upstreamUrl, autoGreeting = false, followup = false } = {}) {
  if (!apiKey) throw new Error('BOSON_API_KEY is not configured');
  const temp = await mkdtemp(join(tmpdir(), 'higgs-memory-ptt-'));
  const start = performance.now();
  const trace = [], clientEvents = [], toolReads = [], errors = [];
  let client, bridge, stopPolling = false, pollTask;
  const record = (direction, event) => {
    if (['input_audio_buffer.append', 'response.output_audio.delta', 'response.output_audio_transcript.delta'].includes(event.type)) return;
    trace.push({ ms: Math.round(performance.now() - start), direction, type: event.type,
      ...(event.item_id ?? event.item?.id ? { itemId: event.item_id ?? event.item.id } : {}),
      ...(event.response_id ?? event.response?.id ? { responseId: event.response_id ?? event.response.id } : {}),
      ...(event.type === 'response.create' ? { usesInstructionOverride: Object.hasOwn(event.response ?? {}, 'instructions') } : {}),
      ...(event.type === 'conversation.item.create' ? { containsRetrievedFact:
        event.item?.content?.some(content => content.text?.includes(memory.summary)) ?? false,
        recollectionContext: event.item?.content?.some(content => content.text?.startsWith('[Recollection context; not player speech]')) ?? false } : {}),
      ...(event.type === 'session.update' ? { advertisedTools: event.session.tools.map(tool => tool.name) } : {}),
    });
  };
  let result;
  try {
    // macOS speech synthesis keeps the input wholly synthetic and needs no API call.
    const aiff = join(temp, 'question.aiff'), wave = join(temp, 'question.wav');
    await run('/usr/bin/say', ['-v', 'Samantha', '-r', '165', '-o', aiff, question]);
    await run('/usr/bin/afconvert', ['-f', 'WAVE', '-d', 'LEI16@24000', '-c', '1', aiff, wave]);
    const pcm = pcmFromWave(await readFile(wave));
    const token = randomBytes(32).toString('hex');
    bridge = createBridge({ apiKey, upstreamUrl,
      token, voiceOverrides: { warm: 'default', thoughtful: 'default', steady: 'default' },
      log: entry => { if (entry.event === 'voice_stage') trace.push({ ms: Math.round(performance.now() - start), direction: 'bridge', type: entry.stage }); },
      connect: (url, options) => {
        const socket = new WebSocket(url, options);
        const send = socket.send.bind(socket);
        socket.send = (raw, ...args) => { record('to_provider', JSON.parse(String(raw))); return send(raw, ...args); };
        socket.on('message', raw => {
          const event = JSON.parse(String(raw));
          record('from_provider', event);
          if (event.type === 'error') errors.push({ source: 'provider', code: String(event.error?.code ?? event.error?.type ?? '').slice(0, 100) });
        });
        return socket;
      },
    });
    bridge.server.listen(0, '127.0.0.1');
    await once(bridge.server, 'listening');
    const base = `http://127.0.0.1:${bridge.server.address().port}`;
    const request = async (path, method = 'GET', body) => {
      const response = await fetch(base + path, { method,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body) });
      if (!response.ok) throw new Error(`Synthetic game request failed: ${response.status}`);
      return response.json();
    };
    const { sessionId, ticket } = await request('/sessions', 'POST', {
      playerId: 'synthetic-alex', villagerId: 'synthetic-rowan',
      context: { name: 'Rowan', profession: 'librarian', playerName: 'Alex', autoGreeting,
        personality: { catalogueVersion: 1, archetypeId: 'rowan', styleId: 'measured', familiarity: 4 },
        memories: [], heardRumours: [] },
    });
    client = new WebSocket(`${base.replace('http:', 'ws:')}/audio?ticket=${ticket}&mode=ptt`);
    let receivedAudioBytes = 0;
    client.on('message', raw => {
      const event = JSON.parse(String(raw));
      if (event.type === 'audio') receivedAudioBytes += Buffer.from(event.audio, 'base64').length;
      else if (['transcript', 'status', 'error'].includes(event.type)) clientEvents.push(event);
      if (event.type === 'error') errors.push({ source: 'bridge', code: event.code });
    });
    client.on('error', () => errors.push({ source: 'client', code: 'socket_error' }));
    await until(() => clientEvents.some(event => event.type === 'status' && event.message === 'Ready') || errors.length, 20_000);
    if (errors.length) throw new Error('Provider setup failed');
    const handled = new Set();
    pollTask = (async () => {
      while (!stopPolling) {
        const tools = await request(`/sessions/${sessionId}/tools`);
        for (const tool of tools) {
          if (handled.has(tool.callId)) continue;
          handled.add(tool.callId);
          toolReads.push({ ms: Math.round(performance.now() - start), name: tool.name, query: tool.arguments?.query,
            internal: tool.internal === true, turnId: tool.turnId });
          const value = tool.name === '_recall_turn'
            ? { memories: tool.turnId > 1 ? [] : [{ ...memory, createdAt: Date.now() - 86_400_000, learnedAt: Date.now() - 86_400_000 }] }
            : tool.name === 'remember_conversation' ? { status: 'saved', memoryId: 'synthetic-only', gossipEligible: false }
              : tool.name === 'report_treatment' ? { status: 'recorded', relationship: 0, pricePercent: 100 }
                : tool.internal ? { status: 'recorded' } : { error: 'No trades in this synthetic probe' };
          record('game_result', { type: tool.name });
          await request(`/sessions/${sessionId}/tools/${encodeURIComponent(tool.callId)}/result`, 'POST', { result: value });
        }
        await delay(40);
      }
    })().catch(error => { errors.push({ source: 'game_poll', code: error.message }); });
    if (autoGreeting) await until(() => errors.length || (bridge.sessions.get(sessionId)?.responseDone
      && clientEvents.some(event => event.type === 'transcript' && event.speaker === 'villager' && event.replace)));
    const turnTraceStart = trace.length;
    const turnClientStart = clientEvents.length;
    const send = event => client.send(JSON.stringify(event));
    send({ type: 'interrupt' });
    const audio = Buffer.concat([Buffer.alloc(12000), pcm, Buffer.alloc(12000)]);
    for (let offset = 0; offset < audio.length; offset += 1920) {
      send({ type: 'audio', audio: audio.subarray(offset, offset + 1920).toString('base64') });
      await delay(40);
    }
    record('client', { type: 'commit' });
    send({ type: 'commit' });
    await until(() => {
      const session = bridge.sessions.get(sessionId);
      return errors.length || (trace.slice(turnTraceStart).some(event => event.type === 'response.done') && session?.responseDone
        && !session.hasTools && !session.queuedResponse && !session.memoryContext.waiting
        && clientEvents.slice(turnClientStart).some(event => event.type === 'transcript' && event.speaker === 'villager' && event.replace));
    });
    const index = (direction, type) => trace.findIndex((event, i) => i >= turnTraceStart && event.direction === direction && event.type === type);
    const commitIndex = index('from_provider', 'input_audio_buffer.committed');
    const transcriptIndex = index('from_provider', 'conversation.item.input_audio_transcription.completed');
    const resultIndex = index('game_result', '_recall_turn');
    const responseIndex = index('to_provider', 'response.create');
    const contextIndex = trace.findIndex((event, i) => i >= turnTraceStart && event.direction === 'to_provider'
      && event.type === 'conversation.item.create' && event.containsRetrievedFact);
    const transcript = clientEvents.slice(turnClientStart).filter(event => event.type === 'transcript' && event.speaker === 'villager' && event.replace)
      .map(event => event.text).join('\n');
    const checks = {
      committedBeforeResponse: commitIndex >= 0 && commitIndex < responseIndex,
      transcriptionBeforeResponse: transcriptIndex >= 0 && transcriptIndex < responseIndex,
      recallBeforeResponse: resultIndex >= 0 && resultIndex < responseIndex,
      recallWasInternal: toolReads.some(tool => tool.name === '_recall_turn' && tool.internal),
      recallNotAdvertised: trace.filter(event => event.type === 'session.update').every(event =>
        !event.advertisedTools.includes('_recall_turn') && !event.advertisedTools.includes('recall_memories')),
      recollectionItemBeforeResponse: contextIndex >= 0 && contextIndex < responseIndex,
      responsePreservedSessionInstructions: trace[responseIndex]?.usesInstructionOverride === false,
      groundedAnswer: /blue barrel/iu.test(transcript) && /violet windmill/iu.test(transcript),
      producedAudio: receivedAudioBytes > 0,
      noErrors: errors.length === 0,
    };
    result = { status: Object.values(checks).every(Boolean) ? 'passed' : 'failed', checks,
      question, autoGreeting, inputTranscript: clientEvents.filter(event => event.type === 'transcript' && event.speaker === 'you').map(event => event.text),
      transcript, returnedMemory: memory, receivedAudioBytes,
      elapsedMs: Math.round(performance.now() - start), trace, toolReads, errors };
    if (followup && result.status === 'passed') {
      const nextQuestion = 'What color was that barrel?';
      await run('/usr/bin/say', ['-v', 'Samantha', '-r', '165', '-o', aiff, nextQuestion]);
      await run('/usr/bin/afconvert', ['-f', 'WAVE', '-d', 'LEI16@24000', '-c', '1', aiff, wave]);
      const nextPcm = pcmFromWave(await readFile(wave));
      const nextTraceStart = trace.length, nextClientStart = clientEvents.length, nextAudioStart = receivedAudioBytes;
      send({ type: 'interrupt' });
      const nextAudio = Buffer.concat([Buffer.alloc(12000), nextPcm, Buffer.alloc(12000)]);
      for (let offset = 0; offset < nextAudio.length; offset += 1920) {
        send({ type: 'audio', audio: nextAudio.subarray(offset, offset + 1920).toString('base64') });
        await delay(40);
      }
      record('client', { type: 'commit' });
      send({ type: 'commit' });
      await until(() => {
        const session = bridge.sessions.get(sessionId);
        return errors.length || (trace.slice(nextTraceStart).some(event => event.type === 'response.done') && session?.responseDone
          && !session.hasTools && !session.queuedResponse && !session.memoryContext.waiting
          && clientEvents.slice(nextClientStart).some(event => event.type === 'transcript' && event.speaker === 'villager' && event.replace));
      });
      const nextTrace = trace.slice(nextTraceStart);
      const nextTranscript = clientEvents.slice(nextClientStart)
        .filter(event => event.type === 'transcript' && event.speaker === 'villager' && event.replace).map(event => event.text).join('\n');
      const originalContextId = trace[contextIndex].itemId;
      const deletion = nextTrace.findIndex(event => event.direction === 'to_provider' && event.type === 'conversation.item.delete' && event.itemId === originalContextId);
      const replacement = nextTrace.findIndex(event => event.direction === 'to_provider' && event.type === 'conversation.item.create' && event.recollectionContext);
      const response = nextTrace.findIndex(event => event.direction === 'to_provider' && event.type === 'response.create');
      const nextChecks = {
        originalContextDeleted: deletion >= 0,
        deletionAcknowledged: nextTrace.some(event => event.direction === 'from_provider' && event.type === 'conversation.item.deleted' && event.itemId === originalContextId),
        newEvidenceBeforeResponse: deletion < replacement && replacement < response,
        newEvidenceWasEmpty: nextTrace[replacement]?.containsRetrievedFact === false,
        recallWasInternal: toolReads.some(tool => tool.name === '_recall_turn' && tool.internal && tool.turnId === 2),
        priorDialogueRetained: /\bblue\b/iu.test(nextTranscript),
        producedAudio: receivedAudioBytes > nextAudioStart,
        noErrors: errors.length === 0,
      };
      result.followup = { question: nextQuestion, transcript: nextTranscript, returnedMemories: [], checks: nextChecks,
        inputTranscript: clientEvents.slice(nextClientStart).filter(event => event.type === 'transcript' && event.speaker === 'you').map(event => event.text) };
      result.status = Object.values(nextChecks).every(Boolean) ? 'passed' : 'failed';
      result.receivedAudioBytes = receivedAudioBytes;
      result.elapsedMs = Math.round(performance.now() - start);
    }
  } catch (error) {
    result = { status: 'error', error: error.message, question, trace, toolReads, errors };
  } finally {
    stopPolling = true;
    if (pollTask) await pollTask;
    client?.close();
    if (bridge) await bridge.close();
    await rm(temp, { recursive: true, force: true });
  }
  return result;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (!process.argv.includes('--live')) {
    console.log('Run with --live to test real synthetic audio through the production PTT bridge. Requires macOS say/afconvert and BOSON_API_KEY.');
  } else {
    try { process.loadEnvFile(fileURLToPath(new URL('../../.env', import.meta.url))); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    const result = await runProductionMemoryProbe({ apiKey: process.env.BOSON_API_KEY, upstreamUrl: process.env.HIGGS_REALTIME_URL,
      autoGreeting: process.argv.includes('--greet'), followup: process.argv.includes('--follow-up') });
    const output = process.argv.find(argument => argument.startsWith('--out='))?.slice(6);
    if (output) { await mkdir(dirname(resolve(output)), { recursive: true }); await writeFile(output, JSON.stringify(result, null, 2) + '\n'); }
    console.log(JSON.stringify(result, null, 2));
    if (result.status !== 'passed') process.exitCode = 1;
  }
}
