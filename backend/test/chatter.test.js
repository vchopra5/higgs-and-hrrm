import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge } from '../src.js';
import { chatterConfig, nextSpeaker, parseScene } from '../lib/chatter.js';

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

async function fixture(t, { answer = true, rateLimit = 0, lines: scripted } = {}) {
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
        socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', transcript: scripted?.[turn - 1] ?? `${name} line ${turn}.` }));
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
  assert.equal(prompts[1][0], 'Iris');
  assert.equal(prompts[1][1], '[Bram says] Bram line 1.\n[Your turn] Reply to Bram in your own new words; never reuse their sentences. One or two short sentences only.');
  assert.equal(prompts[2][1], '[Iris says] Iris line 2.\n[Your turn] Reply to Iris in your own new words; never reuse their sentences. Bring the chat to a natural close. One or two short sentences only.',
    'in a two-way chat nothing was missed');
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
  // Lines are only announced once complete; this provider never answers, so wait for the first prompt instead.
  await until(() => f.received.some(e => e.type === 'response.create'));
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
  assert.match(config.instructions, /^You are Iris, a Minecraft villager who works as a librarian\. You are chatting casually with your neighbour Bram, the village farmer/);
  assert.match(config.instructions, /never address the listener/);
  assert.match(config.instructions, /"playerHitMeRecently":true/);
  assert.match(chatterConfig(parseScene(scene({ speakers: [{ ...scene().speakers[0], facts: { stanceOnPlayer: 'praise' } }, scene().speakers[1]] })), 0)
    .session.instructions, /"stanceOnPlayer":"praise"/);
  assert.equal(config.audio.input.turn_detection, null);
  assert.equal(config.tools, undefined, 'chatter can never call trade or memory tools');
});

test('whisper visits use the literal style tag only in Realtime instructions and expose bounded playback progress', async t => {
  const f = await fixture(t);
  const body = scene({ topic: 'rumour', delivery: 'whisper', lines: 2,
    shared: { story: { summary: 'Alex found a library under the well', toldByPlayer: 'Alex', heardFrom: 'Bram' } } });
  assert.throws(() => parseScene({ ...body, delivery: '<|style:shouting|>' }));
  assert.throws(() => parseScene({ ...body, lines: 7 }));
  assert.throws(() => parseScene({ ...body, topic: 'player' }));
  assert.doesNotMatch(chatterConfig(parseScene(scene()), 0).session.instructions, /<\|style:whispering\|>/);
  const { sceneId, token: sceneToken } = await (await f.request('/scenes', 'POST', body)).json();
  assert.equal((await fetch(`${f.base}/scenes/${sceneId}`)).status, 401, 'progress stays on authenticated server API');
  const { events } = f.listen(sceneId, sceneToken);
  await until(() => events.some(e => e.type === 'end'));
  const status = await (await f.request(`/scenes/${sceneId}`)).json();
  assert.equal(status.finished, true);
  assert.equal(status.lines, 2);
  assert.ok(status.playbackRemainingMs > 0, 'generation finishing must not release villagers before audio plays');
  for (const update of f.received.filter(e => e.type === 'session.update')) {
    assert.equal(update.session.model, 'higgs-realtime');
    assert.match(update.session.instructions, /<\|style:whispering\|>$/);
    assert.match(update.session.instructions, /Discuss only shared.story/);
    assert.equal(update.session.tools, undefined);
  }
  for (const item of f.received.filter(e => e.type === 'conversation.item.create'))
    assert.doesNotMatch(item.item.content[0].text, /<\|style:/, 'style belongs in instructions, not spoken input');
  await delay(status.playbackRemainingMs + 20);
  assert.equal((await (await f.request(`/scenes/${sceneId}`)).json()).playbackRemainingMs, 0);
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

test('three villagers: a named neighbour answers next, a goodbye ends the scene early, and the talk becomes memory', async t => {
  const f = await fixture(t, { lines: ['Alden, have you seen Itzahria about?', 'Aye, Bram. Gave Iris a diamond, I hear.',
    'Generous soul, that one.', 'Well, goodnight to you both.'] });
  const three = scene({ lines: 7, playerId: 'player-uuid', speakers: [
    { ...scene().speakers[0], villagerId: '11111111-1111-4111-8111-111111111111' },
    { ...scene().speakers[1], villagerId: '22222222-2222-4222-8222-222222222222' },
    { entityId: 13, name: 'Alden', profession: 'mason', villagerId: '33333333-3333-4333-8333-333333333333' }] });
  const { sceneId, token: sceneToken } = await (await f.request('/scenes', 'POST', three)).json();
  const { events } = f.listen(sceneId, sceneToken);
  await until(() => events.some(e => e.type === 'end'));
  // Bram names Alden, so Alden answers out of turn; then turns rotate; the goodbye ends it before seven lines.
  assert.deepEqual(events.filter(e => e.type === 'line').map(e => e.speaker), [11, 13, 11, 12]);
  assert.equal(events.at(-1).reason, 'concluded');
  const prompts = f.received.filter(e => e.type === 'conversation.item.create').map(e => e.item.content[0].text);
  assert.match(prompts[0], /^\[Scene start, not player speech\] Begin a short chat with Iris and Alden\./);
  // Iris speaks fourth, after Bram and Alden: she hears Bram's line and the one from Alden she missed.
  assert.match(prompts[3], /^\[Earlier, Alden said\] Aye, Bram\. Gave Iris a diamond, I hear\.\n\[Bram says\] Generous soul, that one\.\n\[Your turn\] Reply to Bram/);
  const config = f.received.find(e => e.type === 'session.update' && e.session.instructions.startsWith('You are Alden')).session;
  assert.match(config.instructions, /chatting casually with your neighbours Bram, the village farmer and Iris, the village librarian/);
  let digests = [];
  for (let attempt = 0; attempt < 100 && !digests.length; attempt++) {
    digests = await (await f.request('/digests')).json();
    if (!digests.length) await delay(20);
  }
  assert.equal(digests[0].kind, 'scene');
  assert.equal(digests[0].playerId, 'player-uuid');
  assert.equal(digests[0].participants.length, 3);
  assert.deepEqual(digests[0].lines.map(line => line.name), ['Bram', 'Alden', 'Bram', 'Iris']);
});

test('chatter turn-taking and scene validation', () => {
  const speakers = [{ name: 'Bram' }, { name: 'Iris' }, { name: 'Alden 2' }];
  assert.equal(nextSpeaker(speakers, 0, 'What do you say, Alden?'), 2, 'named by call name');
  assert.equal(nextSpeaker(speakers, 0, 'Lovely day.'), 1, 'otherwise rotate');
  assert.equal(nextSpeaker(speakers, 2, 'Lovely day.'), 0);
  assert.equal(nextSpeaker(speakers, 1, 'Iris here, and I say no.'), 2, 'naming yourself hands nothing back');
  assert.throws(() => parseScene(scene({ speakers: [...scene().speakers, { entityId: 13, name: 'A', profession: 'mason' }, { entityId: 14, name: 'B', profession: 'mason' }] })), 'at most three');
  assert.throws(() => parseScene(scene({ speakers: [scene().speakers[0], scene().speakers[1], { ...scene().speakers[0] }] })), 'no duplicates');
  assert.throws(() => parseScene(scene({ playerId: 7 })));
});

test('a line that only echoes the last one is never heard: one fresh attempt replaces it', async t => {
  const f = await fixture(t, { lines: ["The harvest looks fine this year, doesn't it?", "The harvest looks fine this year, doesn't it?",
    'Fine enough, though the rain worries me.', 'Rain or not, we will manage.'] });
  const { sceneId, token: sceneToken } = await (await f.request('/scenes', 'POST', scene({ lines: 3 }))).json();
  const { events } = f.listen(sceneId, sceneToken);
  await until(() => events.some(e => e.type === 'end'));
  assert.deepEqual(events.filter(e => e.type === 'line_end').map(e => e.text),
    ["The harvest looks fine this year, doesn't it?", 'Fine enough, though the rain worries me.', 'Rain or not, we will manage.']);
  assert.equal(events.filter(e => e.type === 'audio').length, 3, 'the echo\'s audio was dropped');
  const retry = f.received.filter(e => e.type === 'conversation.item.create').map(e => e.item.content[0].text)[2];
  assert.match(retry, /^\[Your last attempt only repeated what was already said; it was not heard\] Say something different to Bram/);
  assert.ok(f.logs.some(e => e.event === 'chatter_repeat' && e.retried === false));
});
