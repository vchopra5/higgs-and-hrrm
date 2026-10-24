import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge } from '../src.js';

const token = 'b'.repeat(64);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function until(predicate) {
  for (let attempt = 0; attempt < 200; attempt++) {
    if (predicate()) return;
    await delay(10);
  }
  throw new Error('Timed out waiting for a bridge event');
}

async function fixture(t, { now = Date.now, connectAudio = true } = {}) {
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
      if (event.type === 'session.update') {
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
    playerId: 'player', villagerId: 'villager', context: { profession: 'farmer' },
  });
  assert.equal(response.status, 201);
  const { sessionId, ticket } = await response.json();
  const audioUrl = base.replace('http:', 'ws:') + '/audio?ticket=' + ticket;
  let client;
  if (connectAudio) {
    client = new WebSocket(audioUrl);
    client.on('message', data => events.push(JSON.parse(data)));
    await once(client, 'open');
    await until(() => events.some(event => event.message === 'Ready'));
  }
  return {
    bridge, events, logs, received, request, sessionId, client, audioUrl,
    emit: event => upstream.send(JSON.stringify(event)),
  };
}

test('late audio and trade calls from an interrupted response cannot affect the next turn', async t => {
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


test('hit reactions escalate, coalesce and cannot execute trade tools or count as consent', async t => {
  let clock = 1000;
  const f = await fixture(t, { now: () => clock });
  const path = `/sessions/${f.sessionId}/events`;
  assert.equal((await f.request(path, 'POST', {type:'hit', level:4})).status, 400);
  assert.equal((await f.request(path, 'POST', {type:'hit', level:1})).status, 202);
  await until(() => f.received.some(e => e.type === 'response.create'));
  assert.match(f.received.find(e => e.type === 'response.create').response.instructions, /Startled/);
  const session = f.bridge.sessions.get(f.sessionId);
  assert.equal(session.turnId, 0);
  f.emit({ type:'response.created', response:{id:'reaction1'} });
  f.emit({ type:'response.function_call_arguments.done', response_id:'reaction1', call_id:'bad-trade', name:'confirm_trade', arguments:JSON.stringify({proposalId:'a'.repeat(36)}) });
  await until(() => f.received.some(e => e.item?.call_id === 'bad-trade'));
  assert.equal(session.pending.size, 0);
  await f.request(path, 'POST', {type:'hit', level:2});
  await f.request(path, 'POST', {type:'hit', level:3});
  assert.equal(f.received.filter(e => e.type === 'response.create').length, 1);
  f.emit({ type:'response.done', response:{id:'reaction1',status:'completed'} });
  await until(() => session.responseDone);
  session.sweep();
  await until(() => f.received.filter(e => e.type === 'response.create').length === 2);
  assert.match(f.received.filter(e => e.type === 'response.create')[1].response.instructions, /Very upset/);
  assert.equal(session.turnId, 0);
  session.environmentEvent({type:'hit',level:1});
  clock += 11000;
  f.emit({ type:'response.done', response:{id:'reaction1',status:'completed'} });
  await until(() => session.responseDone);
  session.sweep();
  assert.equal(session.pendingReaction, null);
});
