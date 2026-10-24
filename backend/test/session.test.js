import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge } from '../src.js';

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

async function fixture(t, { now = Date.now, connectAudio = true, context = { profession: 'farmer' }, confirmConfig = true, mode = 'vad' } = {}) {
  const provider = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(provider, 'listening');
  const received = [];
  const events = [];
  const logs = [];
  let upstream;
  provider.on('connection', socket => {
    upstream = socket;
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
    playerId: 'player', villagerId: 'villager', context,
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
  f.emit({ type: 'response.created', response: { id: 'late-greeting' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'late-greeting', delta: 'AAA=' });
  f.emit({ type: 'response.done', response: { id: 'late-greeting', status: 'cancelled' } });
  await until(() => f.received.filter(event => event.type === 'response.create').length === 2);
  f.emit({ type: 'response.created', response: { id: 'answer-first-turn' } });
  f.emit({ type: 'response.output_audio.delta', response_id: 'answer-first-turn', delta: 'AQI=' });
  await until(() => f.events.some(event => event.audio === 'AQI='));
  assert.equal(f.events.some(event => event.audio === 'AAA='), false);
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

test('memory tools use the game-server route and resume only after the full tool batch', async t => {
  const f = await fixture(t);
  const config = f.received.find(event => event.type === 'session.update').session;
  assert.ok(config.tools.some(tool => tool.name === 'remember_conversation'));
  assert.ok(config.tools.some(tool => tool.name === 'recall_memories'));
  f.emit({ type: 'response.created', response: { id: 'memory-turn' } });
  for (const [callId, name, args] of [
    ['remember', 'remember_conversation', memoryArgs()],
    ['recall', 'recall_memories', { query: '', scope: 'player' }],
  ]) {
    const call = { type: 'response.function_call_arguments.done', response_id: 'memory-turn', call_id: callId, name, arguments: JSON.stringify(args) };
    f.emit(call);
    f.emit(call);
  }
  f.emit({ type: 'response.done', response: { id: 'memory-turn', status: 'completed' } });
  await until(() => f.bridge.sessions.get(f.sessionId).responseDone && f.bridge.sessions.get(f.sessionId).pending.size === 2);
  const pending = await (await f.request(`/sessions/${f.sessionId}/tools`)).json();
  assert.deepEqual(pending.map(tool => tool.callId), ['remember', 'recall']);
  const base = `/sessions/${f.sessionId}/tools`;
  await f.request(`${base}/remember/result`, 'POST', { result: { status: 'saved', private: false } });
  assert.equal(f.received.filter(event => event.type === 'response.create').length, 0);
  await f.request(`${base}/recall/result`, 'POST', { result: { memories: [] } });
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
