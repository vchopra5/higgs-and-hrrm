import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge } from '../src.js';
import { chatterConfig, parseScene } from '../lib/chatter.js';

const token = 'c'.repeat(64);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate) {
  for (let attempt = 0; attempt < 300; attempt++) {
    if (predicate()) return;
    await delay(10);
  }
  throw new Error('Timed out waiting for a chatter event');
}

const scene = (overrides = {}) => ({
  topic: 'player', lines: 3, shared: { playerName: 'Itzahria', time: 'evening' },
  speakers: [
    { entityId: 11, name: 'Bram', profession: 'farmer', facts: { opinionOfPlayer: 'friendly' },
      personality: { catalogueVersion: 1, archetypeId: 'nope', styleId: 'nope' } },
    { entityId: 12, name: 'Iris', profession: 'librarian', facts: { opinionOfPlayer: 'annoyed', playerHitMeRecently: true } },
  ],
  ...overrides,
});

async function fixture(t, { answer = true, rateLimit = 0 } = {}) {
  const provider = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(provider, 'listening');
  const received = [];
  const connections = [];
  let limited = 0;
  provider.on('connection', socket => {
    connections.push(Date.now());
    let name = '?';
    socket.on('message', data => {
      const event = JSON.parse(data);
      received.push({ ...event, socketName: name });
      if (event.type === 'session.update') {
        name = event.session.instructions.match(/^You are (\w+)/)[1];
        if (name === 'Iris' && limited < rateLimit) {
          limited++;
          socket.send(JSON.stringify({ type: 'error', error: { type: 'invalid_request_error', code: '400',
            message: "Session terminated due to Error while handling session update: Could not validate voice 'chloe': voices API returned HTTP 429" } }));
          socket.close();
          return;
        }
        socket.send(JSON.stringify({ type: 'session.updated', session: event.session }));
      }
      if (event.type === 'response.create' && answer) {
        const turn = received.filter(e => e.type === 'response.create').length;
        socket.send(JSON.stringify({ type: 'response.output_audio.delta', delta: Buffer.from(`${name}${turn}`).toString('base64') }));
        socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', transcript: `${name} line ${turn}.` }));
        socket.send(JSON.stringify({ type: 'response.done', response: { status: 'completed' } }));
      }
    });
  });
  const logs = [];
  const bridge = createBridge({ token, apiKey: 'fake', upstreamUrl: `ws://127.0.0.1:${provider.address().port}`, log: e => logs.push(e) });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  t.after(async () => {
    await bridge.close();
    for (const client of provider.clients) client.terminate();
    await new Promise(resolve => provider.close(resolve));
  });
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const request = (path, method = 'GET', body) => fetch(base + path, {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const listen = (sceneId, sceneToken) => {
    const events = [];
    const client = new WebSocket(`${base.replace('http:', 'ws:')}/chatter?scene=${sceneId}&token=${sceneToken}`);
    client.on('message', data => events.push(JSON.parse(data)));
    return { client, events };
  };
  return { bridge, received, logs, request, listen, base, connections };
}

test('two villagers alternate lines, each answering the last, and listeners hear every line in order', async t => {
  const f = await fixture(t);
  const response = await f.request('/scenes', 'POST', scene());
  assert.equal(response.status, 201);
  const { sceneId, token: sceneToken } = await response.json();
  const { events } = f.listen(sceneId, sceneToken);
  await until(() => events.some(e => e.type === 'end'));
  const lines = events.filter(e => e.type === 'line');
  assert.deepEqual(lines.map(e => e.speaker), [11, 12, 11]);
  assert.deepEqual(events.filter(e => e.type === 'line_end').map(e => e.text), ['Bram line 1.', 'Iris line 2.', 'Bram line 3.']);
  assert.equal(events.filter(e => e.type === 'audio').length, 3);
  assert.equal(events.at(-1).reason, 'completed');
  // Each reply is prompted with the neighbour's previous line, never as player speech.
  const prompts = f.received.filter(e => e.type === 'conversation.item.create').map(e => [e.socketName, e.item.content[0].text]);
  assert.match(prompts[0][1], /^\[Scene start, not player speech\] Begin a short chat with Iris\. Gossip about Itzahria/);
  assert.deepEqual(prompts[1], ['Iris', '[Bram says to you] Bram line 1.']);
  assert.match(prompts[2][1], /^\[Iris says to you\] Iris line 2\. Bring the chat to a natural close/);
  assert.ok(f.logs.some(e => e.event === 'chatter_finished' && e.reason === 'completed'));
});

test('a listener joining late replays the scene, and scenes are bounded and authenticated', async t => {
  const f = await fixture(t);
  const { sceneId, token: sceneToken } = await (await f.request('/scenes', 'POST', scene({ lines: 2 }))).json();
  await until(() => f.logs.some(e => e.event === 'chatter_finished'));
  const late = f.listen(sceneId, sceneToken);
  await until(() => late.events.some(e => e.type === 'end'));
  assert.deepEqual(late.events.filter(e => e.type === 'line_end').map(e => e.text), ['Bram line 1.', 'Iris line 2.']);
  const wrong = new WebSocket(`${f.base.replace('http:', 'ws:')}/chatter?scene=${sceneId}&token=${'0'.repeat(64)}`);
  await once(wrong, 'error');
  assert.equal((await f.request('/scenes', 'POST', scene({ lines: 9 }))).status, 400);
  assert.equal((await f.request('/scenes', 'POST', scene({ topic: 'secrets' }))).status, 400);
  assert.equal((await f.request(`/scenes/${sceneId}`, 'DELETE')).status, 200);
  assert.equal(f.bridge.scenes.has(sceneId), false);
});

test('a stopped scene ends for its listeners and frees its slot', async t => {
  const f = await fixture(t, { answer: false });
  const first = await (await f.request('/scenes', 'POST', scene())).json();
  await f.request('/scenes', 'POST', scene());
  assert.equal((await f.request('/scenes', 'POST', scene())).status, 429);
  const { client, events } = f.listen(first.sceneId, first.token);
  await once(client, 'open');
  await until(() => events.some(e => e.type === 'line'));
  await f.request(`/scenes/${first.sceneId}`, 'DELETE');
  await until(() => events.some(e => e.type === 'end'));
  assert.equal((await f.request('/scenes', 'POST', scene())).status, 201);
});

test('scene validation and speaker personas', () => {
  assert.throws(() => parseScene(scene({ speakers: [scene().speakers[0]] })));
  assert.throws(() => parseScene(scene({ speakers: [scene().speakers[0], scene().speakers[0]] })), 'two distinct villagers');
  assert.throws(() => parseScene(scene({ shared: { notes: 'x'.repeat(9000) } })), 'facts are bounded');
  const parsed = parseScene(scene());
  const config = chatterConfig(parsed, 1).session;
  assert.match(config.instructions, /^You are Iris, a Minecraft villager who works as a librarian\. You are chatting casually with your neighbour Bram/);
  assert.match(config.instructions, /never address the listener/);
  assert.match(config.instructions, /"playerHitMeRecently":true/);
  assert.match(chatterConfig(parseScene(scene({ speakers: [{ ...scene().speakers[0], facts: { stanceOnPlayer: 'praise' } }, scene().speakers[1]] })), 0)
    .session.instructions, /"stanceOnPlayer":"praise"/);
  assert.equal(config.audio.input.turn_detection, null);
  assert.equal(config.tools, undefined, 'chatter can never call trade or memory tools');
});

test('villagers connect one at a time, and a rate-limited voice check is retried', async t => {
  const f = await fixture(t, { rateLimit: 1 });
  const { sceneId, token: sceneToken } = await (await f.request('/scenes', 'POST', scene({ lines: 2 }))).json();
  const { events } = f.listen(sceneId, sceneToken);
  await until(() => events.some(e => e.type === 'end'));
  assert.equal(events.at(-1).reason, 'completed');
  assert.deepEqual(events.filter(e => e.type === 'line_end').map(e => e.text), ['Bram line 1.', 'Iris line 2.']);
  assert.equal(f.connections.length, 3, 'Bram, Iris rate limited, Iris retried');
  assert.ok(f.logs.some(e => e.event === 'chatter_setup_retry' && e.speaker === 1 && e.attempt === 1));
  const updates = f.received.filter(e => e.type === 'session.update').map(e => e.socketName === '?' ? e.session.instructions.slice(8, 12) : e.socketName);
  assert.equal(updates[0], 'Bram', 'the second villager waits for the first');
});
