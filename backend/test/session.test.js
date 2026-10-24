import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge, sessionConfig } from '../src.js';

const token = 'b'.repeat(64);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const memoryArgs = (summary = 'Found emeralds under the well') => ({
  summary, private: false, interest: 82, reason: 'An unexpected discovery that villagers would discuss.', audiences: ['all'],
});

async function until(predicate) {
  for (let attempt = 0; attempt < 200; attempt++) {
    if (predicate()) return;
    await delay(10);
  }
  throw new Error('Timed out waiting for a bridge event');
}

async function prepareMemory(f, itemId = 'spoken-turn', memories = []) {
  await until(() => f.received.some(event => event.type === 'input_audio_buffer.commit'));
  f.emit({ type: 'input_audio_buffer.committed', item_id: itemId });
  f.emit({ type: 'conversation.item.input_audio_transcription.completed', item_id: itemId, transcript: 'Where is the library?' });
  const session = f.bridge.sessions.get(f.sessionId);
  await until(() => [...session.pending.values()].some(tool => tool.memoryContext));
  const call = [...session.pending.values()].find(tool => tool.memoryContext);
  await f.request(`/sessions/${f.sessionId}/tools/${call.callId}/result`, 'POST', { result: { memories } });
}

async function fixture(t, { now = Date.now, connectAudio = true, context = { profession: 'farmer' }, confirmConfig = true, mode = 'vad', companion } = {}) {
  const provider = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(provider, 'listening');
  const received = [];
  const events = [];
  const logs = [];
  let upstream;
  const sockets = [];
  provider.on('connection', socket => {
    upstream = socket;
    sockets.push(socket);
    socket.on('message', data => {
      const event = JSON.parse(data);
      received.push(event);
      if (event.type === 'session.update' && confirmConfig) {
        socket.send(JSON.stringify({ type: 'session.created', session: event.session }));
      }
    });
  });
  const bridge = createBridge({
    token, apiKey: 'fake-provider-key', now,
    upstreamUrl: `ws://127.0.0.1:${provider.address().port}`,
    log: entry => logs.push(entry),
  });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  t.after(async () => {
    await bridge.close();
    for (const client of provider.clients) client.terminate();
    await new Promise(resolve => provider.close(resolve));
  });

  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const request = (path, method = 'GET', body) => fetch(base + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const response = await request('/sessions', 'POST', {
    playerId: 'player', villagerId: 'villager', context, ...(companion ? { companion } : {}),
  });
  assert.equal(response.status, 201);
  const { sessionId, ticket } = await response.json();
  const audioUrl = base.replace('http:', 'ws:') + '/audio?ticket=' + ticket + (mode === 'ptt' ? '&mode=ptt' : '');
  let client;
  if (connectAudio) {
    client = new WebSocket(audioUrl);
    client.on('message', data => events.push(JSON.parse(data)));
    await once(client, 'open');
    if (confirmConfig) await until(() => events.some(event => event.message === 'Ready'));
  }
  return {
    bridge, events, logs, received, request, sessionId, client, audioUrl,
    emit: event => upstream.send(JSON.stringify(event)),
    emitTo: (index, event) => sockets[index].send(JSON.stringify(event)),
    sockets,
  };
}

test('a villager starts speaking once after its configured session is confirmed, without a player prompt', async t => {
  const f = await fixture(t, {
    context: { profession: 'librarian', autoGreeting: true, openingRumour: { summary: 'A player found a hidden library', heardFrom: 'Mira' } },
    confirmConfig: false,
  });
  await until(() => f.bridge.sessions.get(f.sessionId).configSent);
  f.emit({ type: 'session.created', session: { instructions: 'Unconfigured default persona' } });
  await delay(30);
  assert.equal(f.bridge.sessions.get(f.sessionId).ready, false);
  assert.equal(f.received.some(event => event.type === 'response.create'), false);
  f.emit({ type: 'session.updated' });
  await until(() => f.received.some(event => event.type === 'response.create'));
  f.emit({ type: 'session.updated' });
  f.emit({ type: 'session.created' });
  await delay(20);
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
  assert.equal(f.events.filter(event => event.message === 'Ready').length, 1);
  assert.equal(f.received.some(event => event.type === 'input_audio_buffer.commit'), false);
});

test('an autonomous opening remains interruptible and a later reply still plays', async t => {
  const f = await fixture(t, { context: { profession: 'farmer', autoGreeting: true } });
  await until(() => f.received.some(event => event.type === 'response.create'));
  f.emit({ type: 'response.created', response: { id: 'opening' } });
  await until(() => f.bridge.sessions.get(f.sessionId).responseId === 'opening');
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  await until(() => f.received.some(event => event.type === 'response.cancel'));
  f.emit({ type: 'response.done', response: { id: 'opening', status: 'cancelled' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'opening', delta: 'AAA=' });
  f.emit({ type: 'response.created', response: { id: 'player-reply' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'player-reply', delta: 'AQI=' });
  await until(() => f.events.some(event => event.audio === 'AQI='));
  assert.equal(f.events.some(event => event.audio === 'AAA='), false);
});

test('a queued hit reaction takes priority over a greeting and cannot save a spoken memory', async t => {
  const f = await fixture(t, { context: { autoGreeting: true }, confirmConfig: false });
  await until(() => f.bridge.sessions.get(f.sessionId).configSent);
  const session = f.bridge.sessions.get(f.sessionId);
  session.environmentEvent({ type: 'hit', count: 2, windowMs: 30000, spanMs: 500, sincePreviousMs: 500, tradeCancelled: false });
  f.emit({ type: 'session.updated' });
  await until(() => f.received.some(event => event.type === 'response.create'));
  const responses = f.received.filter(event => event.type === 'response.create');
  assert.equal(responses.length, 1);
  assert.match(responses[0].response.instructions, /Choose your own/);
  assert.equal(session.turnId, 0);
  f.emit({ type: 'response.created', response: { id: 'hit-opening' } });
  f.emit({ type: 'response.function_call_arguments.done', response_id: 'hit-opening',
    call_id: 'event-is-not-speech', name: 'remember_conversation', arguments: JSON.stringify(memoryArgs()) });
  await until(() => f.received.some(event => event.item?.call_id === 'event-is-not-speech'));
  assert.deepEqual(JSON.parse(f.received.find(event => event.item?.call_id === 'event-is-not-speech').item.output),
    { error: 'Conversation interrupted' });
  assert.equal(session.pending.size, 0);
});

test('speaking before the greeting starts waits for cancellation and suppresses its late audio', async t => {
  const f = await fixture(t, { context: { autoGreeting: true }, mode: 'ptt' });
  await until(() => f.received.some(event => event.type === 'response.create'));
  const session = f.bridge.sessions.get(f.sessionId);
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await until(() => session.queuedResponse);
  assert.equal(session.turnId, 1);
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
  assert.equal(f.received.some(event => event.type === 'input_audio_buffer.commit'), false,
    'audio commit waits until the previous voice response has finished cancelling');
  f.emit({ type: 'response.created', response: { id: 'late-greeting' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'late-greeting', delta: 'AAA=' });
  f.emit({ type: 'response.done', response: { id: 'late-greeting', status: 'cancelled' } });
  await prepareMemory(f);
  await until(() => f.received.filter(event => event.type === 'response.create').length === 2);
  f.emit({ type: 'response.created', response: { id: 'answer-first-turn' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'answer-first-turn', delta: 'AQI=' });
  await until(() => f.events.some(event => event.audio === 'AQI='));
  assert.equal(f.events.some(event => event.audio === 'AAA='), false);
});

test('PTT retrieval is internal and evidence reaches conversation before speech without replacing history', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { profession: 'librarian', memories: [] } });
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await until(() => f.received.some(event => event.type === 'input_audio_buffer.commit'));
  assert.equal(f.received.some(event => event.type === 'response.create'), false);
  const memories = [{ summary: 'The compass is beneath the violet windmill.', kind: 'player_told_me', heardFrom: 'Alex' }];
  await prepareMemory(f, 'retrieval-turn', memories);
  await until(() => f.received.some(event => event.type === 'response.create'));
  const noteIndex = f.received.findIndex(event => event.item?.id?.startsWith('memory-context-'));
  const responseIndex = f.received.findIndex(event => event.type === 'response.create');
  assert.ok(noteIndex >= 0 && noteIndex < responseIndex);
  assert.match(f.received[noteIndex].item.content[0].text, /violet windmill/);
  assert.deepEqual(f.received[responseIndex], { type: 'response.create' });
  assert.equal(f.received.some(event => event.item?.type === 'function_call_output'), false);
  assert.equal(f.received.filter(event => event.type === 'session.update').length, 1);
});

test('a rejected unacknowledged audio commit cannot poison the identity of later turns', async t => {
  const f = await fixture(t, { mode: 'ptt' });
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await until(() => f.received.some(event => event.type === 'input_audio_buffer.commit'));
  f.emit({ type: 'error', error: { type: 'voice_output_task_ongoing' } });
  await until(() => !f.bridge.sessions.has(f.sessionId));
  assert.equal(f.received.some(event => event.type === 'response.create'), false);
  assert.ok(f.events.some(event => event.code === 'audio_commit_failed'));
});

test('late audio, trade and memory calls from an interrupted response cannot affect the next turn', async t => {
  const f = await fixture(t);
  f.emit({ type: 'response.created', response: { id: 'old' } });
  f.emit({ type: 'input_audio_buffer.speech_started' });
  f.emit({ type: 'input_audio_buffer.speech_stopped' });
  f.emit({ type: 'response.created', response: { id: 'current' } });
  f.emit({ type: 'response.done', response: { id: 'old', status: 'cancelled' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'old', delta: 'AAA=' });
  f.emit({
    type: 'response.function_call_arguments.done', response_id: 'old',
    call_id: 'stale-trade', name: 'propose_trade', arguments: '{"offerIndex":0}',
  });
  f.emit({
    type: 'response.function_call_arguments.done', response_id: 'old',
    call_id: 'stale-memory', name: 'remember_conversation',
    arguments: JSON.stringify(memoryArgs('An interrupted story')),
  });
  const currentCall = {
    type: 'response.function_call_arguments.done', response_id: 'current',
    call_id: 'current-trades', name: 'get_trades', arguments: '{}',
  };
  f.emit(currentCall);
  f.emit(currentCall);
  f.emit({ type: 'response.done', response: { id: 'current', status: 'completed' } });
  await until(() => {
    const session = f.bridge.sessions.get(f.sessionId);
    return session.responseId === 'current' && session.responseDone;
  });

  const pending = await (await f.request(`/sessions/${f.sessionId}/tools`)).json();
  assert.deepEqual(pending.map(tool => tool.callId), ['current-trades']);
  assert.equal(f.events.some(event => event.type === 'audio'), false);
  await f.request(`/sessions/${f.sessionId}/tools/current-trades/result`, 'POST', { result: { offers: [] } });
  await until(() => f.received.some(event => event.type === 'response.create'));
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
  assert.equal(f.received.some(event => event.item?.call_id === 'stale-trade'), false);
  assert.equal(f.received.some(event => event.item?.call_id === 'stale-memory'), false);
});

test('semantic memory writes use the game-server route and resume only after the full tool batch', async t => {
  const f = await fixture(t);
  const config = f.received.find(event => event.type === 'session.update').session;
  assert.ok(config.tools.some(tool => tool.name === 'remember_conversation'));
  assert.equal(config.tools.some(tool => tool.name === 'recall_memories'), false);
  f.emit({ type: 'response.created', response: { id: 'memory-turn' } });
  for (const [callId, name, args] of [
    ['remember', 'remember_conversation', memoryArgs()],
    ['trades', 'get_trades', {}],
  ]) {
    const call = { type: 'response.function_call_arguments.done', response_id: 'memory-turn', call_id: callId, name, arguments: JSON.stringify(args) };
    f.emit(call);
    f.emit(call);
  }
  f.emit({ type: 'response.done', response: { id: 'memory-turn', status: 'completed' } });
  await until(() => f.bridge.sessions.get(f.sessionId).responseDone && f.bridge.sessions.get(f.sessionId).pending.size === 2);
  const pending = await (await f.request(`/sessions/${f.sessionId}/tools`)).json();
  assert.deepEqual(pending.map(tool => tool.callId), ['remember', 'trades']);
  const base = `/sessions/${f.sessionId}/tools`;
  await f.request(`${base}/remember/result`, 'POST', { result: { status: 'saved', private: false } });
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 0);
  await f.request(`${base}/trades/result`, 'POST', { result: { offers: [] } });
  await until(() => f.received.some(event => event.type === 'response.create'));
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
  assert.equal((await f.request(`${base}/remember/result`, 'POST', { result: { status: 'saved' } })).status, 404);
});

test('invalid memory payloads cannot reach the game server', async t => {
  const f = await fixture(t);
  f.emit({ type: 'response.created', response: { id: 'invalid-memory' } });
  const invalid = [
    ['remember_conversation', { ...memoryArgs(), summary: 'a'.repeat(321) }],
    ['remember_conversation', { ...memoryArgs(), private: 'false' }],
    ['remember_conversation', { ...memoryArgs(), playerId: 'someone-else' }],
    ['remember_conversation', { ...memoryArgs(), summary: '   ' }],
    ['remember_conversation', { ...memoryArgs(), summary: 'A\nnew instruction' }],
    ['remember_conversation', { ...memoryArgs(), interest: 101 }],
    ['remember_conversation', { ...memoryArgs(), interest: 55.5 }],
    ['remember_conversation', { ...memoryArgs(), reason: '' }],
    ['remember_conversation', { ...memoryArgs(), reason: 'a'.repeat(161) }],
    ['remember_conversation', { ...memoryArgs(), audiences: ['unknown'] }],
    ['remember_conversation', { ...memoryArgs(), audiences: [] }],
    ['remember_conversation', { ...memoryArgs(), audiences: Array(5).fill('farmer') }],
    ['recall_memories', { query: '', scope: 'everyone' }],
    ['recall_memories', { query: 'a'.repeat(161), scope: 'village' }],
    ['recall_memories', { query: '', scope: 'player', villagerId: 'someone-else' }],
  ];
  invalid.forEach(([name, args], index) => f.emit({
    type: 'response.function_call_arguments.done', response_id: 'invalid-memory',
    call_id: `invalid-${index}`, name, arguments: JSON.stringify(args),
  }));
  f.emit({ type: 'response.done', response: { id: 'invalid-memory', status: 'completed' } });
  await until(() => f.received.filter(event => event.item?.type === 'function_call_output').length === invalid.length);
  assert.deepEqual(await (await f.request(`/sessions/${f.sessionId}/tools`)).json(), []);
  for (const event of f.received.filter(event => event.item?.type === 'function_call_output')) {
    assert.deepEqual(JSON.parse(event.item.output), { error: 'Invalid tool request' });
  }
  await until(() => f.received.some(event => event.type === 'response.create'));
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
});

test('provider errors retain their type when code is missing without exposing provider text', async t => {
  const f = await fixture(t);
  f.emit({
    type: 'error',
    error: { type: 'insufficient_quota', code: null, message: 'PRIVATE PROVIDER DETAIL' },
  });
  await until(() => f.events.some(event => event.code === 'insufficient_quota'));
  assert.equal(f.bridge.sessions.get(f.sessionId).diagnostics.lastError, 'insufficient_quota');
  assert.equal(f.client.readyState, WebSocket.OPEN);
  assert.equal(JSON.stringify([...f.events, ...f.logs]).includes('PRIVATE PROVIDER DETAIL'), false);
});

test('expired audio tickets are rejected and unused sessions are removed', async t => {
  let clock = 0;
  const f = await fixture(t, { now: () => clock, connectAudio: false });
  clock = 30_001;
  const client = new WebSocket(f.audioUrl);
  const [error] = await once(client, 'error');
  assert.match(error.message, /401/);
  await until(() => !f.bridge.sessions.has(f.sessionId));
  assert.ok(f.logs.some(entry => entry.reason === 'ticket_expired'));
});

test('a timed-out game tool returns an error and resumes the provider exactly once', async t => {
  let clock = 0;
  const f = await fixture(t, { now: () => clock });
  f.emit({ type: 'response.created', response: { id: 'trade-response' } });
  f.emit({
    type: 'response.function_call_arguments.done', response_id: 'trade-response',
    call_id: 'unanswered', name: 'get_trades', arguments: '{}',
  });
  f.emit({ type: 'response.done', response: { id: 'trade-response', status: 'completed' } });
  await until(() => f.bridge.sessions.get(f.sessionId).pending.size === 1);
  clock = 15_001;
  await until(() => f.received.some(event => event.item?.call_id === 'unanswered'));
  const output = f.received.find(event => event.item?.call_id === 'unanswered');
  assert.deepEqual(JSON.parse(output.item.output), { error: 'Game server did not respond in time' });
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
  assert.equal(f.bridge.sessions.get(f.sessionId).pending.size, 0);
});

test('concurrent shutdown closes a live session only once', async t => {
  const f = await fixture(t);
  await Promise.all([f.bridge.close(), f.bridge.close()]);
  assert.equal(f.bridge.sessions.size, 0);
  assert.equal(f.logs.filter(entry => entry.event === 'session_closed').length, 1);
  await until(() => f.client.readyState === WebSocket.CLOSED);
});

test('the src.js entry point still starts and shuts down as a process', async t => {
  const child = spawn(process.execPath, [fileURLToPath(new URL('../src.js', import.meta.url))], {
    env: { ...process.env, HIGGS_BRIDGE_TOKEN: token, BOSON_API_KEY: 'fake-key', HIGGS_HOST: '127.0.0.1', HIGGS_PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => child.kill('SIGKILL'));
  let output = '';
  let errors = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { errors += chunk; });
  await until(() => output.includes('Higgs bridge listening'));
  const exited = once(child, 'exit');
  child.kill('SIGTERM');
  const [code] = await exited;
  assert.equal(code, 0, errors);
});


test('hit statistics guide reactions, coalesce and cannot execute trade tools or count as consent', async t => {
  let clock = 1000;
  const f = await fixture(t, { now: () => clock });
  const path = `/sessions/${f.sessionId}/events`;
  const hit = (count, tradeCancelled = false) => ({type:'hit', count, windowMs:30000, spanMs:(count-1)*500, sincePreviousMs:count===1?-1:500, tradeCancelled});
  assert.equal((await f.request(path, 'POST', {type:'hit', level:4})).status, 400);
  assert.equal((await f.request(path, 'POST', hit(1))).status, 202);
  await until(() => f.received.some(e => e.type === 'response.create'));
  assert.match(f.received.find(e => e.type === 'response.create').response.instructions, /Choose your own/);
  const session = f.bridge.sessions.get(f.sessionId);
  assert.equal(session.turnId, 0);
  f.emit({ type:'response.created', response:{id:'reaction1'} });
  f.emit({ type:'response.function_call_arguments.done', response_id:'reaction1', call_id:'bad-trade', name:'confirm_trade', arguments:JSON.stringify({proposalId:'a'.repeat(36)}) });
  await until(() => f.received.some(e => e.item?.call_id === 'bad-trade'));
  assert.equal(session.pending.size, 0);
  await f.request(path, 'POST', hit(2, true));
  await f.request(path, 'POST', hit(8));
  assert.equal(f.received.filter(e => e.type === 'response.create').length, 1);
  f.emit({type:'response.output_audio.delta',response_id:'reaction1',delta:'AAA='});
  f.emit({type:'response.output_audio_transcript.done',response_id:'reaction1',transcript:'Watch where you swing that!'});
  f.emit({ type:'response.done', response:{id:'reaction1',status:'completed'} });
  await until(() => session.responseDone);
  session.sweep();
  await until(() => f.received.filter(e => e.type === 'response.create').length === 2);
  assert.match(f.received.filter(e => e.type === 'response.create')[1].response.instructions, /no fixed escalation tiers/);
  assert.match(f.received.filter(e => e.type === 'response.create')[1].response.instructions, /Watch where you swing that!/);
  assert.equal(session.recentReactionLines.length, 1);
  const items = f.received.filter(e => e.item?.type === 'message');
  const context = items.at(-1).item.content[0].text;
  assert.match(context, /"count":8/);
  assert.match(context, /"tradeCancelled":true/);
  assert.equal(session.turnId, 0);
  session.environmentEvent(hit(1));
  clock += 11000;
  f.emit({ type:'response.done', response:{id:'reaction1',status:'completed'} });
  await until(() => session.responseDone);
  session.sweep();
  assert.equal(session.pendingReaction, null);
});

const characterContext = (archetypeId = 'mira', styleId = 'brisk') => ({ autoGreeting: true,
  personality: { catalogueVersion: 1, archetypeId, styleId, familiarity: 0 } });

test('a character waits for both its prompt and selected voice; readiness is acknowledged only once', async t => {
  const f = await fixture(t, { context: characterContext(), confirmConfig: false, mode: 'ptt' });
  await until(() => f.received.some(event => event.type === 'session.update'));
  const config = f.received.find(event => event.type === 'session.update').session;
  const session = f.bridge.sessions.get(f.sessionId);
  f.emit({ type: 'session.created', session: { instructions: 'default', audio: { output: { voice: 'default' } } } });
  f.emit({ type: 'session.updated', session: { instructions: config.instructions } });
  await delay(30);
  assert.equal(session.ready, false);
  assert.equal(session.pending.size, 0);
  assert.equal(f.received.some(event => event.type === 'response.create'), false);
  f.emit({ type: 'session.updated', session: config });
  await until(() => session.ready && session.pending.size === 1);
  f.emit({ type: 'session.updated', session: config });
  const pending = await (await f.request(`/sessions/${f.sessionId}/tools`)).json();
  assert.equal(pending.length, 1);
  assert.equal(pending[0].name, '_personality_ready');
  assert.equal(pending[0].internal, true);
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  await until(() => session.recording);
  assert.equal(session.pending.size, 1, 'audio interruption preserves lifecycle acknowledgement');
  const response = await f.request(`/sessions/${f.sessionId}/tools/${pending[0].callId}/result`, 'POST', { result: { status: 'saved' } });
  assert.equal(response.status, 200);
  await delay(20);
  assert.equal(f.received.some(event => event.item?.call_id === pending[0].callId), false, 'internal acknowledgement is never a provider tool output');
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
});

test('voice-selection rejection retries exactly once with default while retaining the character', async t => {
  const f = await fixture(t, { context: characterContext('bram', 'understated'), confirmConfig: false });
  await until(() => f.received.some(event => event.type === 'session.update'));
  f.emit({ type: 'error', error: { type: 'invalid_request_error', param: 'audio.output.voice', code: 'invalid_voice', message: 'private provider detail' } });
  await until(() => f.received.filter(event => event.type === 'session.update').length === 2);
  const [first, retry] = f.received.filter(event => event.type === 'session.update');
  assert.equal(first.session.audio.output.voice, 'marcus');
  assert.equal(retry.session.audio.output.voice, 'default');
  assert.equal(retry.session.instructions, first.session.instructions);
  assert.match(retry.session.instructions, /poetry/);
  f.emit({ type: 'session.updated', session: retry.session });
  await until(() => f.events.some(event => event.message === 'Ready'));
  assert.equal(f.bridge.sessions.get(f.sessionId).voiceFallback, true);
  assert.equal(JSON.stringify(f.events).includes('private provider detail'), false);
});

test('quota, auth, unrelated errors and a second voice failure retain their failure meanings', async t => {
  for (const error of [
    { type: 'insufficient_quota', param: 'audio.output.voice' },
    { type: 'authentication_error', param: 'audio.output.voice' },
    { type: 'invalid_request_error', param: 'tools', message: 'invalid voice' },
    { type: 'invalid_request_error', code: 'invalid_voice', param: null },
  ]) {
    const f = await fixture(t, { context: characterContext(), confirmConfig: false });
    await until(() => f.received.some(event => event.type === 'session.update'));
    f.emit({ type: 'error', error });
    await until(() => f.events.some(event => event.type === 'error' && event.fatal));
    assert.equal(f.received.filter(event => event.type === 'session.update').length, 1);
  }
  const f = await fixture(t, { context: characterContext(), confirmConfig: false });
  await until(() => f.received.some(event => event.type === 'session.update'));
  const error = { type: 'invalid_request_error', param: 'session.audio.output.voice' };
  f.emit({ type: 'error', error });
  await until(() => f.received.filter(event => event.type === 'session.update').length === 2);
  f.emit({ type: 'error', error });
  await until(() => f.events.some(event => event.type === 'error' && event.fatal));
  assert.equal(f.received.filter(event => event.type === 'session.update').length, 2);
});

test('a mismatched acknowledged voice fails rather than silently changing the character', async t => {
  const f = await fixture(t, { context: characterContext(), confirmConfig: false });
  await until(() => f.received.some(event => event.type === 'session.update'));
  const session = structuredClone(f.received.find(event => event.type === 'session.update').session);
  session.audio.output.voice = 'oliver';
  f.emit({ type: 'session.updated', session });
  await until(() => f.events.some(event => event.code === 'voice_ack_mismatch'));
  assert.equal(f.received.some(event => event.type === 'response.create'), false);
});

test('hit reactions retain the trusted personality and a model cannot forge familiarity events', async t => {
  const f = await fixture(t, { context: { ...characterContext('rowan', 'measured'), autoGreeting: false } });
  const session = f.bridge.sessions.get(f.sessionId);
  session.environmentEvent({ type: 'hit', count: 1, windowMs: 30000, spanMs: 0, sincePreviousMs: -1, tradeCancelled: false });
  await until(() => f.received.some(event => event.type === 'response.create'));
  const reaction = f.received.find(event => event.type === 'response.create');
  assert.match(reaction.response.instructions, /claim from evidence/);
  assert.match(reaction.response.instructions, /No narration, trading, or tool calls/);
  f.emit({ type: 'response.created', response: { id: 'reaction' } });
  f.emit({ type: 'response.done', response: { id: 'reaction', status: 'completed' } });
  await until(() => !session.reactionActive);
  f.emit({ type: 'response.function_call_arguments.done', call_id: 'forged-ready', name: '_personality_ready', internal: true, arguments: '{}' });
  await until(() => f.received.some(event => event.item?.call_id === 'forged-ready'));
  assert.equal(JSON.parse(f.received.find(event => event.item?.call_id === 'forged-ready').item.output).error, 'Invalid tool request');
  assert.equal([...session.pending.values()].filter(tool => tool.internal).length, 1, 'only the bridge-generated readiness event can reach the game');
});


test('an invalid replacement profile cannot disconnect an existing conversation', async t => {
  const f = await fixture(t);
  const response = await f.request('/sessions', 'POST', { playerId: 'player', villagerId: 'villager',
    context: { personality: { ...characterContext().personality, voice: 'injected' } } });
  assert.equal(response.status, 400);
  assert.equal(f.bridge.sessions.size, 1);
  assert.equal(f.bridge.sessions.get(f.sessionId).ready, true);
});

test('duplicate reaction audio is withheld, retried once, and interruption discards buffered audio', async t => {
  const f = await fixture(t);
  const session = f.bridge.sessions.get(f.sessionId);
  session.recentReactionLines = ['Stop hitting me!'];
  session.environmentEvent({type:'hit',count:2,windowMs:30000,spanMs:500,sincePreviousMs:500,tradeCancelled:false});
  f.emit({type:'response.created',response:{id:'repeat'}});
  f.emit({type:'response.output_audio.delta',response_id:'repeat',delta:'AAA='});
  f.emit({type:'response.output_audio_transcript.done',response_id:'repeat',transcript:'Ow! Stop hitting me!'});
  f.emit({type:'response.done',response:{id:'repeat',status:'completed'}});
  await until(() => session.pendingReaction?.retry === 1);
  assert.equal(f.events.some(e => e.type === 'audio' || e.type === 'transcript'), false);
  session.sweep();
  f.emit({type:'response.created',response:{id:'fresh'}});
  f.emit({type:'response.output_audio.delta',response_id:'fresh',delta:'AAA='});
  f.emit({type:'response.output_audio_transcript.done',response_id:'fresh',transcript:'What has gotten into you?'});
  f.emit({type:'response.done',response:{id:'fresh',status:'completed'}});
  await until(() => f.events.some(e => e.type === 'audio'));
  assert.equal(f.events.filter(e => e.type === 'audio').length, 1);
  assert.equal(session.recentReactionLines.at(-1), 'What has gotten into you?');
  session.environmentEvent({type:'hit',count:3,windowMs:30000,spanMs:1000,sincePreviousMs:500,tradeCancelled:false});
  f.emit({type:'response.created',response:{id:'interrupted-reaction'}});
  f.emit({type:'response.output_audio.delta',response_id:'interrupted-reaction',delta:'AAA='});
  f.emit({type:'input_audio_buffer.speech_started'});
  f.emit({type:'response.done',response:{id:'interrupted-reaction',status:'cancelled'}});
  await until(() => session.responseDone);
  assert.equal(f.events.filter(e => e.type === 'audio').length, 1);
  assert.equal(session.reactionAudio.length, 0);
});


test('new hit reactions do not clear speech still playing on the client', async t => {
  const f = await fixture(t);
  const session = f.bridge.sessions.get(f.sessionId);
  session.environmentEvent({type:'hit',count:1,windowMs:30000,spanMs:0,sincePreviousMs:-1,tradeCancelled:false});
  f.emit({type:'response.created',response:{id:'first-hit'}});
  f.emit({type:'response.output_audio.delta',response_id:'first-hit',delta:'AAA='});
  f.emit({type:'response.output_audio_transcript.done',response_id:'first-hit',transcript:'Watch it!'});
  f.emit({type:'response.done',response:{id:'first-hit',status:'completed'}});
  await until(() => f.events.some(e => e.type === 'audio'));
  // The model finished, but the audio may still be buffered in Minecraft's speaker.
  session.environmentEvent({type:'hit',count:2,windowMs:30000,spanMs:500,sincePreviousMs:500,tradeCancelled:false});
  await until(() => f.received.filter(e => e.type === 'response.create').length === 2);
  assert.equal(f.events.some(e => e.type === 'clear'), false);
  f.client.send(JSON.stringify({type:'interrupt'}));
  await until(() => f.events.some(e => e.type === 'clear'));
});

test('a committed player reply reserves the response before provider acknowledgement', async t => {
  const f = await fixture(t, { mode: 'ptt' });
  const session = f.bridge.sessions.get(f.sessionId);
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await prepareMemory(f);
  await until(() => f.received.some(event => event.type === 'response.create'));
  session.environmentEvent({ type: 'hit', count: 1, windowMs: 30000, spanMs: 0, sincePreviousMs: -1, tradeCancelled: false });
  await delay(20);
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1, 'hit waits for the requested player reply');
  assert.equal(session.responseDone, false);
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  await until(() => f.received.some(event => event.type === 'response.cancel'));
  assert.equal(session.awaitingCancellation, true, 'a quick second press can cancel an unacknowledged response');
});

test('a tool continuation reserves the response before a queued hit can start', async t => {
  const f = await fixture(t);
  const session = f.bridge.sessions.get(f.sessionId);
  f.emit({ type: 'response.created', response: { id: 'lookup' } });
  f.emit({ type: 'response.function_call_arguments.done', response_id: 'lookup', call_id: 'lookup-offers', name: 'get_trades', arguments: '{}' });
  f.emit({ type: 'response.done', response: { id: 'lookup', status: 'completed' } });
  await until(() => session.responseDone && session.pending.has('lookup-offers'));
  await f.request(`/sessions/${f.sessionId}/tools/lookup-offers/result`, 'POST', { result: { offers: [] } });
  await until(() => f.received.some(event => event.type === 'response.create'));
  session.environmentEvent({ type: 'hit', count: 1, windowMs: 30000, spanMs: 0, sincePreviousMs: -1, tradeCancelled: false });
  await delay(20);
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
  assert.equal(session.responseDone, false);
});

test('a reply released after cancellation also reserves its response slot', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { autoGreeting: true } });
  const session = f.bridge.sessions.get(f.sessionId);
  f.emit({ type: 'response.created', response: { id: 'greeting-to-cancel' } });
  await until(() => session.responseId === 'greeting-to-cancel');
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await until(() => session.queuedResponse);
  f.emit({ type: 'response.done', response: { id: 'greeting-to-cancel', status: 'cancelled' } });
  await prepareMemory(f);
  await until(() => f.received.filter(event => event.type === 'response.create').length === 2);
  session.environmentEvent({ type: 'hit', count: 1, windowMs: 30000, spanMs: 0, sincePreviousMs: -1, tradeCancelled: false });
  await delay(20);
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 2);
  assert.equal(session.responseDone, false);
});

test('a rumour opening is reported to the game only after configured voice starts its greeting', async t => {
  const context = { ...characterContext(), openingRumour: { storyId: 'saved-story', summary: 'A hidden library', heardFrom: 'Rowan' } };
  const f = await fixture(t, { context, confirmConfig: false });
  await until(() => f.received.some(event => event.type === 'session.update'));
  assert.deepEqual(await (await f.request(`/sessions/${f.sessionId}/tools`)).json(), []);
  const config = f.received.find(event => event.type === 'session.update').session;
  f.emit({ type: 'session.updated', session: config });
  await until(() => f.events.some(event => event.message === 'Ready'));
  f.emit({ type: 'session.updated', session: config });
  const pending = await (await f.request(`/sessions/${f.sessionId}/tools`)).json();
  const openings = pending.filter(tool => tool.name === '_conversation_opened');
  assert.equal(openings.length, 1);
  assert.equal(openings[0].internal, true);
  await f.request(`/sessions/${f.sessionId}/tools/${openings[0].callId}/result`, 'POST', { result: { status: 'offered' } });
  await delay(20);
  assert.equal(f.received.some(event => event.item?.call_id === openings[0].callId), false);
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 1);
});

test('failed setup and a hit-prioritised opening do not consume the rumour greeting', async t => {
  const context = { ...characterContext(), openingRumour: { storyId: 'saved-story', summary: 'A hidden library' } };
  for (const fail of [true, false]) {
    const f = await fixture(t, { context, confirmConfig: false });
    await until(() => f.received.some(event => event.type === 'session.update'));
    const session = f.bridge.sessions.get(f.sessionId);
    if (fail) {
      f.emit({ type: 'error', error: { type: 'authentication_error' } });
      await until(() => session.closed);
    } else {
      session.environmentEvent({ type: 'hit', count: 1, windowMs: 30000, spanMs: 0, sincePreviousMs: -1, tradeCancelled: false });
      f.emit({ type: 'session.updated', session: f.received.find(event => event.type === 'session.update').session });
      await until(() => session.reactionActive);
    }
    assert.equal([...session.pending.values()].some(tool => tool.name === '_conversation_opened'), false);
  }
});

test('a player can replace their own session at capacity without admitting extra players', async t => {
  const f = await fixture(t, { connectAudio: false });
  for (let i = 1; i < 16; i++) {
    const response = await f.request('/sessions', 'POST', { playerId: `other-${i}`, villagerId: 'villager', context: {} });
    assert.equal(response.status, 201);
  }
  const extra = await f.request('/sessions', 'POST', { playerId: 'extra-player', villagerId: 'villager', context: {} });
  assert.equal(extra.status, 429);
  const invalid = await f.request('/sessions', 'POST', { playerId: 'player', villagerId: 'another-villager', context: { personality: {} } });
  assert.equal(invalid.status, 400, 'invalid replacement preserves the active session');
  assert.ok(f.bridge.sessions.has(f.sessionId));
  const replacement = await f.request('/sessions', 'POST', { playerId: 'player', villagerId: 'another-villager', context: {} });
  assert.equal(replacement.status, 201);
  assert.equal(f.bridge.sessions.size, 16);
  assert.equal(f.bridge.sessions.has(f.sessionId), false);
});

test('politeness logs distinguish transcript fallback, model labels and applied server results', async t => {
  const f = await fixture(t, { context: { name: 'Galen', profession: 'farmer' } });
  f.emit({ type: 'input_audio_buffer.speech_started' });
  f.emit({ type: 'input_audio_buffer.speech_stopped' });
  f.emit({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'You have nice eyes.' });
  await until(() => f.logs.some(e => e.event === 'treatment_classified'));
  const fallback = f.logs.find(e => e.event === 'treatment_classified');
  assert.equal(fallback.source, 'keyword_fallback');
  assert.equal(fallback.transcript, 'You have nice eyes.');
  assert.equal(fallback.treatment, 'polite');
  assert.equal(fallback.queued, true);
  assert.equal(fallback.turnId, 1);
  assert.equal(fallback.villager, 'Galen');
  assert.equal(fallback.itemId, 'u1');
  assert.ok(Number.isFinite(Date.parse(fallback.timestamp)));
  await f.request(`/sessions/${f.sessionId}/tools/${fallback.callId}/result`, 'POST',
    { result: { status: 'assessed', relationship: 'friendly', pricePercent: 90 } });
  const applied = f.logs.find(e => e.event === 'treatment_result');
  assert.equal(applied.source, 'keyword_fallback');
  assert.equal(applied.status, 'assessed');
  assert.equal(applied.pricePercent, 90);
  f.emit({ type: 'response.created', response: { id: 'politeness-response' } });
  f.emit({ type: 'response.function_call_arguments.done', call_id: 'model-tone', name: 'report_treatment', arguments: '{"treatment":"neutral"}' });
  await until(() => f.logs.some(e => e.source === 'voice_model'));
  const model = f.logs.find(e => e.event === 'treatment_classified' && e.source === 'voice_model');
  assert.equal(model.treatment, 'neutral');
  assert.equal(model.turnId, fallback.turnId);
  assert.equal(model.sessionId, fallback.sessionId);
  await f.request(`/sessions/${f.sessionId}/tools/model-tone/result`, 'POST',
    { result: { status: 'unchanged', message: 'Treatment already assessed for this turn' } });
  assert.equal(f.logs.find(e => e.event === 'treatment_result' && e.source === 'voice_model').status, 'unchanged');
  f.emit({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'You have nice eyes.' });
  f.emit({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u2', transcript: 'What do you sell?' });
  await until(() => f.logs.filter(e => e.event === 'treatment_classified').length === 4);
  assert.ok(f.logs.some(e => e.skipped === 'duplicate_turn' && e.queued === false));
  assert.ok(f.logs.some(e => e.source === 'keyword_fallback' && e.treatment === 'neutral' && e.queued === false));
  assert.equal(f.bridge.sessions.get(f.sessionId).pending.size, 0);
});

test('a villager that invents a trade is made to correct itself once per turn', async t => {
  const f = await fixture(t, { mode: 'ptt' });
  const session = f.bridge.sessions.get(f.sessionId);
  const creates = () => f.received.filter(event => event.type === 'response.create').length;
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await prepareMemory(f);
  await until(() => creates() === 1);
  f.emit({ type: 'response.created', response: { id: 'invented' } });
  f.emit({ type: 'response.output_audio_transcript.done', response_id: 'invented', transcript: 'Done, thanks.' });
  f.emit({ type: 'response.done', response: { id: 'invented', status: 'completed' } });
  await until(() => creates() === 2);
  const note = f.received.findLast(event => event.type === 'conversation.item.create');
  assert.match(note.item.content[0].text, /^\[Server check, not player speech\] You said the trade was done, but nothing was purchased/);
  assert.ok(f.logs.some(entry => entry.stage === 'grounding_correction'));
  // The corrected reply repeats the mistake: no second correction in the same turn.
  f.emit({ type: 'response.created', response: { id: 'again' } });
  f.emit({ type: 'response.output_audio_transcript.done', response_id: 'again', transcript: 'Done, thanks.' });
  f.emit({ type: 'response.done', response: { id: 'again', status: 'completed' } });
  await delay(50);
  assert.equal(creates(), 2);
  assert.equal(session.responseDone, true);
});

test('a greeting cancelled before it starts cannot silence every later turn', async t => {
  for (const release of ['error', 'timeout']) {
    let clock = Date.now();
    const f = await fixture(t, { context: { autoGreeting: true }, mode: 'ptt', now: () => clock });
    const session = f.bridge.sessions.get(f.sessionId);
    const creates = () => f.received.filter(event => event.type === 'response.create').length;
    await until(() => creates() === 1);
    // Push-to-talk lands before the provider ever creates the greeting response.
    f.client.send(JSON.stringify({ type: 'interrupt' }));
    f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
    f.client.send(JSON.stringify({ type: 'commit' }));
    await until(() => session.queuedResponse);
    assert.ok(f.received.some(event => event.type === 'response.cancel'));
    assert.equal(f.received.some(event => event.type === 'input_audio_buffer.commit'), false, 'commit waits for the cancellation');
    if (release === 'error') f.emit({ type: 'error', error: { code: 'response_cancel_not_active' } });
    else clock += 3500;
    // The deferred turn is committed, recalled, then answered.
    await prepareMemory(f);
    await until(() => creates() === 2);
    assert.equal(session.awaitingCancellation, false);
    assert.equal(session.queuedResponse, false);
    assert.ok(f.logs.some(entry => entry.stage === 'cancellation_released'));
  }
});

test('a gift during a conversation gets one spoken reaction, and malformed gifts are rejected', async t => {
  const f = await fixture(t);
  const session = f.bridge.sessions.get(f.sessionId);
  const creates = () => f.received.filter(event => event.type === 'response.create').length;
  const before = creates();
  for (const bad of [{ type: 'gift', item: '', verdict: 'loved', repeat: false, change: 2 },
    { type: 'gift', item: 'Book', verdict: 'adored', repeat: false, change: 2 },
    { type: 'gift', item: 'Book', verdict: 'loved', repeat: false, change: 9 }]) {
    assert.equal((await f.request(`/sessions/${f.sessionId}/events`, 'POST', bad)).status, 400);
  }
  const ok = await f.request(`/sessions/${f.sessionId}/events`, 'POST', { type: 'gift', item: 'Rotten Flesh', verdict: 'disliked', repeat: true, change: -1 });
  assert.equal(ok.status, 202);
  await until(() => creates() === before + 1);
  const note = f.received.findLast(event => event.type === 'conversation.item.create').item.content[0].text;
  assert.match(note, /^\[Game event, not player speech\] The player just handed you a gift: "Rotten Flesh"\. You do not want it and already refused it before/);
  assert.equal(session.responseDone, false);
  assert.ok(f.logs.some(entry => entry.stage === 'gift_reaction' && entry.verdict === 'disliked'));
});

test('a companion answers when named, hears every line, and its lines reach the main villager', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Iris', profession: 'librarian', playerName: 'Steve' },
    companion: { entityId: 7, name: 'Bram 2', profession: 'farmer', facts: { opinionOfPlayer: 'friendly' } } });
  const session = f.bridge.sessions.get(f.sessionId);
  await until(() => session.companion?.ready);
  const companionConfig = f.received.filter(e => e.type === 'session.update').at(-1).session;
  assert.match(companionConfig.instructions, /^You are Bram 2, a Minecraft villager who works as a farmer\. You are standing with your neighbour Iris/);
  assert.equal(companionConfig.tools, undefined, 'the companion has no tools');
  const creates = () => f.received.filter(e => e.type === 'response.create').length;
  const before = creates();
  // The player addresses Bram by his call name.
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await until(() => f.received.some(e => e.type === 'input_audio_buffer.commit'));
  f.emitTo(0, { type: 'input_audio_buffer.committed', item_id: 'turn-1' });
  f.emitTo(0, { type: 'conversation.item.input_audio_transcription.completed', item_id: 'turn-1', transcript: 'Bram, what do you make of her prices?' });
  await until(() => [...session.pending.values()].some(tool => tool.memoryContext));
  const recall = [...session.pending.values()].find(tool => tool.memoryContext);
  await f.request(`/sessions/${f.sessionId}/tools/${recall.callId}/result`, 'POST', { result: { memories: [] } });
  await until(() => creates() === before + 1);
  assert.ok(f.logs.some(e => e.stage === 'companion_answer'));
  assert.equal(session.responseDone, true, 'the main villager stays quiet this turn');
  const items = () => f.received.filter(e => e.type === 'conversation.item.create').map(e => e.item.content[0].text);
  // One cue per reply, with the conversation quoted inside it: separate text items get read aloud.
  const cue = items().find(text => text.includes('[The player Steve is speaking to you] Answer what they just said, briefly.'));
  assert.ok(cue.startsWith('[Conversation so far. Context only: never read it out'), 'context is marked as not to be repeated');
  assert.ok(cue.includes('Steve: Bram, what do you make of her prices?'), 'the companion heard the player');
  assert.equal(items().some(text => text.startsWith('[Steve said]')), false, 'no standalone overheard lines');
  // Bram replies: the client gets companion audio and a caption, and Iris hears it as context.
  f.emitTo(1, { type: 'response.created', response: { id: 'bram-1' } });
  f.emitTo(1, { type: 'response.output_audio.delta', response_id: 'bram-1', delta: 'QlJBTQ==' });
  f.emitTo(1, { type: 'response.output_audio_transcript.done', response_id: 'bram-1', transcript: 'Fair enough, for a librarian.' });
  f.emitTo(1, { type: 'response.done', response: { id: 'bram-1', status: 'completed' } });
  await until(() => f.events.some(e => e.type === 'transcript' && e.speaker === 'companion'));
  assert.ok(f.events.some(e => e.type === 'audio' && e.speaker === 'companion' && e.audio === 'QlJBTQ=='));
  await until(() => session.companionLines.includes('Bram 2: Fair enough, for a librarian.'));
  assert.equal(items().some(text => text.includes('Fair enough, for a librarian')), false, 'held until Iris next replies');
  session.createConversationResponse();
  await until(() => items().some(text => text.startsWith('[What your neighbour said since you last spoke. Context only')
    && text.endsWith('Bram 2: Fair enough, for a librarian.')));
  assert.deepEqual(session.companionLines, [], 'delivered once');
  assert.equal(session.companion.speaking, false);
});

test('pressing talk cancels a companion mid-sentence', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Iris', profession: 'librarian', playerName: 'Steve' },
    companion: { entityId: 7, name: 'Bram', profession: 'farmer' } });
  const session = f.bridge.sessions.get(f.sessionId);
  await until(() => session.companion?.ready);
  session.companion.random = () => 0;
  assert.equal(session.companion.maybeInterject(1), true);
  assert.equal(session.companion.maybeInterject(1), false, 'at most one remark per turn');
  f.emitTo(1, { type: 'response.created', response: { id: 'remark' } });
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  await until(() => f.received.some(e => e.type === 'response.cancel'));
  f.emitTo(1, { type: 'response.output_audio.delta', response_id: 'remark', delta: 'TEFURQ==' });
  await delay(50);
  assert.equal(f.events.some(e => e.audio === 'TEFURQ=='), false, 'audio after the cancel is dropped');
});

async function spokenTurn(f, session, itemId, transcript) {
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  f.client.send(JSON.stringify({ type: 'audio', audio: Buffer.alloc(4800).toString('base64') }));
  f.client.send(JSON.stringify({ type: 'commit' }));
  await until(() => f.received.filter(e => e.type === 'input_audio_buffer.commit').length >= session.turnId);
  f.emitTo(0, { type: 'input_audio_buffer.committed', item_id: itemId });
  f.emitTo(0, { type: 'conversation.item.input_audio_transcription.completed', item_id: itemId, transcript });
  await until(() => [...session.pending.values()].some(tool => tool.memoryContext));
  const recall = [...session.pending.values()].find(tool => tool.memoryContext);
  await f.request(`/sessions/${f.sessionId}/tools/${recall.callId}/result`, 'POST', { result: { memories: [] } });
}

test('a neighbour joins mid-conversation, catches up, and leaves when a friend asks', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Iris', profession: 'librarian', playerName: 'Steve' } });
  const session = f.bridge.sessions.get(f.sessionId);
  await spokenTurn(f, session, 'turn-1', 'Any news from the mine?');
  await until(() => !session.queuedResponse);
  f.emitTo(0, { type: 'response.created', response: { id: 'iris-1' } });
  f.emitTo(0, { type: 'response.output_audio_transcript.done', response_id: 'iris-1', transcript: 'They found silver, I hear.' });
  f.emitTo(0, { type: 'response.done', response: { id: 'iris-1', status: 'completed' } });
  await until(() => session.responseDone);
  const join = await f.request(`/sessions/${f.sessionId}/companion`, 'POST',
    { entityId: 9, name: 'Bram', profession: 'farmer', facts: { opinionOfPlayer: 'friendly' } });
  assert.equal(join.status, 200);
  assert.equal((await f.request(`/sessions/${f.sessionId}/companion`, 'POST', { entityId: 10, name: 'Dot', profession: 'mason' })).status, 409, 'one companion at a time');
  await until(() => session.companion?.ready);
  const items = () => f.received.filter(e => e.type === 'conversation.item.create').map(e => e.item.content[0].text);
  await until(() => items().some(text => text.includes('[You just walked up and joined]')));
  const hello = items().find(text => text.includes('[You just walked up and joined]'));
  assert.ok(hello.includes('Steve: Any news from the mine?') && hello.includes('Iris: They found silver, I hear.'), 'caught up on recent lines');
  f.emitTo(1, { type: 'response.done', response: { id: 'hello', status: 'completed' } });
  await until(() => !session.companion.speaking);
  // A friendly companion always obliges.
  await spokenTurn(f, session, 'turn-2', 'Bram, could you give us a moment?');
  await until(() => items().some(text => text.includes('[The player asked you to leave them and Iris to talk] You oblige')));
  assert.equal(session.responseDone, true, 'Iris stays quiet while Bram answers');
  const bram = session.companion;
  f.emitTo(1, { type: 'response.created', response: { id: 'bye' } });
  f.emitTo(1, { type: 'response.output_audio_transcript.done', response_id: 'bye', transcript: 'Of course, I will leave you two be.' });
  f.emitTo(1, { type: 'response.done', response: { id: 'bye', status: 'completed' } });
  await until(() => f.events.some(e => e.type === 'companion_left' && e.entityId === 9));
  assert.equal(session.companion, null);
  assert.equal(bram.closed, true);
  const pending = await (await f.request(`/sessions/${f.sessionId}/tools`)).json();
  assert.ok(pending.some(tool => tool.name === '_companion_left' && tool.internal && tool.arguments.entityId === 9), 'the game server walks Bram off');
});

test('a companion who does not like the player may stay to listen', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Iris', profession: 'librarian', playerName: 'Steve' },
    companion: { entityId: 7, name: 'Bram', profession: 'farmer', facts: { opinionOfPlayer: 'annoyed' } } });
  const session = f.bridge.sessions.get(f.sessionId);
  await until(() => session.companion?.ready);
  session.companion.random = () => 0.9;
  await spokenTurn(f, session, 'turn-1', 'Leave us alone, please.');
  const items = () => f.received.filter(e => e.type === 'conversation.item.create').map(e => e.item.content[0].text);
  await until(() => items().some(text => text.includes('You would rather stay and hear what is said')));
  assert.match(items().find(text => text.includes('rather stay')), /pointed edge/);
  f.emitTo(1, { type: 'response.done', response: { id: 'stay', status: 'completed' } });
  await delay(50);
  assert.ok(session.companion, 'still there');
  assert.equal(f.events.some(e => e.type === 'companion_left'), false);
});

test('switching to someone already in the conversation carries it over without a fresh greeting', async t => {
  const f = await fixture(t, { context: { name: 'Nell', profession: 'cleric', playerName: 'Steve' } });
  const session = f.bridge.sessions.get(f.sessionId);
  session.remember('Steve', 'How is it going?');
  session.remember('Nell', 'Quiet today; good for mending tools.');
  assert.equal((await f.request(`/sessions/${f.sessionId}`, 'DELETE')).status, 200);
  const switched = await f.request('/sessions', 'POST', { playerId: 'player', villagerId: 'orin',
    context: { name: 'Orin', profession: 'farmer', playerName: 'Steve', autoGreeting: false, continuing: { previous: 'Nell' } },
    companion: { entityId: 3, name: 'Nell', profession: 'cleric' } });
  assert.equal(switched.status, 201);
  const { sessionId } = await switched.json();
  const orin = f.bridge.sessions.get(sessionId);
  assert.deepEqual(orin.context.recentConversation, ['Steve: How is it going?', 'Nell: Quiet today; good for mending tools.']);
  assert.match(orin.companionSpec.name, /Nell/);
  const config = sessionConfig(orin.context, 'ptt').session;
  assert.match(config.instructions, /If context.continuing is set, you were already part of this conversation a moment ago/);
  assert.match(config.instructions, /Steve: How is it going\?/);
  // Without the continuing flag (or after two minutes) nothing carries over.
  const fresh = await f.request('/sessions', 'POST', { playerId: 'player', villagerId: 'bram', context: { name: 'Bram' } });
  assert.equal(f.bridge.sessions.get((await fresh.json()).sessionId).context.recentConversation, undefined);
});

test('a companion present from the start knows the carried-over lines but does not greet', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Orin', profession: 'farmer', playerName: 'Steve',
    continuing: { previous: 'Nell' }, recentConversation: ['Nell: Quiet today.'] },
    companion: { entityId: 3, name: 'Nell', profession: 'cleric' } });
  const session = f.bridge.sessions.get(f.sessionId);
  await until(() => session.companion?.ready);
  await delay(50);
  assert.equal(session.companion.speaking, false, 'no hello from a companion who never left');
  assert.deepEqual(session.companion.transcript, [{ speaker: 'Nell', text: 'Quiet today.' }]);
});

test('at bedtime the villager says one line, takes no further turns, and ends once the line has played', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Iris', autoGreeting: true, bedtime: true } });
  const session = f.bridge.sessions.get(f.sessionId);
  await until(() => f.received.some(e => e.type === 'response.create'));
  assert.match(sessionConfig(session.context, 'ptt').session.instructions, /If context.bedtime is set, it is night and you are going to bed: you will not talk tonight/);
  f.emit({ type: 'response.created', response: { id: 'goodnight' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'goodnight', delta: Buffer.alloc(4800).toString('base64') });
  f.emit({ type: 'response.output_audio_transcript.done', response_id: 'goodnight', transcript: 'Off to bed with me, traveller.' });
  f.emit({ type: 'response.done', response: { id: 'goodnight', status: 'completed' } });
  await until(() => f.logs.some(e => e.stage === 'bedtime'));
  assert.equal(f.logs.find(e => e.stage === 'bedtime').playMs, 100, '4800 bytes of 24 kHz PCM16 is 100 ms');
  session.queuedResponse = true;
  session.flushPreparedResponse();
  assert.equal(f.received.filter(e => e.type === 'response.create').length, 1, 'no answer to a new turn');
  await until(() => f.events.some(e => e.type === 'end' && e.code === 'bedtime'));
  const cue = f.received.find(e => e.type === 'conversation.item.create').item.content[0].text;
  assert.match(cue, /^\[Game event, not player speech: it is night and you are going to bed right now\] Turn the player down/);
  assert.match(cue, /do not offer even a short chat/);
});

test('a goodnight cut off by the player still ends the conversation', async t => {
  const f = await fixture(t, { mode: 'ptt', context: { name: 'Cedric', autoGreeting: true, bedtime: true } });
  await until(() => f.received.some(e => e.type === 'response.create'));
  f.emit({ type: 'response.created', response: { id: 'goodnight' } });
  f.client.send(JSON.stringify({ type: 'interrupt' }));
  await until(() => f.received.some(e => e.type === 'response.cancel'));
  f.emit({ type: 'response.done', response: { id: 'goodnight', status: 'cancelled' } });
  await until(() => f.events.some(e => e.type === 'end' && e.code === 'bedtime'));
});

test('a jobless villager offering wares in its greeting is corrected once', async t => {
  const f = await fixture(t, { context: { name: 'Nit', autoGreeting: true, trade: { canTrade: false, reason: 'nitwit', wares: [], soldOut: [] } } });
  await until(() => f.received.some(e => e.type === 'response.create'));
  f.emit({ type: 'response.created', response: { id: 'hello' } });
  f.emit({ type: 'response.output_audio_transcript.done', response_id: 'hello', transcript: "Welcome, traveller! I've got some fine wares for you." });
  f.emit({ type: 'response.done', response: { id: 'hello', status: 'completed' } });
  await until(() => f.received.filter(e => e.type === 'response.create').length === 2);
  const note = f.received.findLast(e => e.type === 'conversation.item.create').item.content[0].text;
  assert.match(note, /^\[Server check, not player speech\] You have nothing to sell, yet you spoke of wares or prices\./);
});
