import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createBridge } from '../src.js';
import { ShoutCache, ShoutScene, parseShout } from '../lib/shout.js';

const token = 'd'.repeat(64);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate) {
  for (let attempt = 0; attempt < 300; attempt++) { if (predicate()) return; await delay(10); }
  throw new Error('Timed out');
}
const speaker = { entityId: 21, name: 'Iris', profession: 'librarian' };

test('a chased villager screams in its own voice, heard through the listen channel', async t => {
  const provider = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(provider, 'listening');
  const prompts = [];
  provider.on('connection', socket => socket.on('message', data => {
    const event = JSON.parse(data);
    if (event.type === 'session.update') socket.send(JSON.stringify({ type: 'session.updated', session: event.session }));
    if (event.type === 'conversation.item.create') prompts.push(event.item.content[0].text);
    if (event.type === 'response.create') {
      socket.send(JSON.stringify({ type: 'response.output_audio.delta', delta: 'QUFBSA==' }));
      socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', transcript: 'Aaah! Zombie! Help!' }));
      socket.send(JSON.stringify({ type: 'response.done', response: { status: 'completed' } }));
    }
  }));
  const bridge = createBridge({ token, apiKey: 'fake', upstreamUrl: `ws://127.0.0.1:${provider.address().port}`, log: () => {} });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  t.after(async () => { await bridge.close(); for (const c of provider.clients) c.terminate(); await new Promise(r => provider.close(r)); });
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const post = body => fetch(base + '/shouts', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  assert.equal((await post({ kind: 'party', speaker })).status, 400);
  const created = await post({ kind: 'zombie', speaker });
  assert.equal(created.status, 201);
  const { sceneId, token: listenToken } = await created.json();
  const events = [];
  const client = new WebSocket(`${base.replace('http:', 'ws:')}/chatter?scene=${sceneId}&token=${listenToken}`);
  client.on('message', data => events.push(JSON.parse(data)));
  await until(() => events.some(e => e.type === 'end'));
  assert.deepEqual(events.map(e => e.type), ['line', 'audio', 'line_end', 'end']);
  assert.equal(events[0].speaker, 21);
  assert.equal(events[2].text, 'Aaah! Zombie! Help!');
  assert.equal(events.at(-1).reason, 'completed', 'not an interruption: the client plays it out');
  assert.match(prompts[0], /^\[A zombie is chasing you right now\] Scream for help/);
  // Shouts never use up the chatter scene slots.
  for (let i = 0; i < 3; i++) await post({ kind: 'zombie', speaker });
  const scene = await fetch(base + '/scenes', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ topic: 'work', lines: 2, speakers: [speaker, { entityId: 22, name: 'Bram', profession: 'farmer' }] }) });
  assert.equal(scene.status, 201);
});

test('screams are cached per voice and replayed without the provider', async () => {
  const cache = new ShoutCache(() => 0.9);
  cache.store('chloe', 'zombie', { audio: ['QUFBSA=='], text: 'Help!' });
  const shout = new ShoutScene({ kind: 'zombie', speaker: { ...speaker, personality: undefined } }, {
    cache, now: Date.now, log: () => {}, onClose: () => {}, connect: () => { throw new Error('must not connect'); },
  });
  shout.voice = 'chloe';
  shout.start();
  assert.deepEqual(shout.replay.map(e => e.type), ['line', 'audio', 'line_end', 'end']);
  assert.equal(shout.replay[2].text, 'Help!');
  assert.equal(shout.replay[0].speaker, 21, 'the cached clip plays from this villager');
  assert.equal(cache.pick('oliver', 'zombie', true), null, 'other voices have nothing cached');
  for (let i = 0; i < 5; i++) cache.store('chloe', 'zombie', { audio: [], text: String(i) });
  assert.equal(cache.clips.get('chloe:zombie').length, 3, 'three variants per voice');
  assert.throws(() => parseShout({ kind: 'zombie', speaker: { entityId: 'x', name: 'Iris', profession: 'librarian' } }));
});

test('a rap is written fresh each time and never cached; bell calls are cached like screams', async t => {
  const provider = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(provider, 'listening');
  t.after(() => new Promise(resolve => { for (const c of provider.clients) c.terminate(); provider.close(resolve); }));
  const prompts = [];
  provider.on('connection', socket => socket.on('message', data => {
    const event = JSON.parse(data);
    if (event.type === 'session.update') socket.send(JSON.stringify({ type: 'session.updated', session: event.session }));
    if (event.type === 'conversation.item.create') prompts.push(event.item.content[0].text);
    if (event.type === 'response.create') {
      socket.send(JSON.stringify({ type: 'response.output_audio.delta', delta: 'QUFBSA==' }));
      socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', transcript: 'I farm the wheat, I keep the beat.' }));
      socket.send(JSON.stringify({ type: 'response.done', response: { status: 'completed' } }));
    }
  }));
  const cache = new ShoutCache(() => 0);
  const run = kind => new Promise(resolve => {
    const shout = new ShoutScene({ kind, speaker: { entityId: 5, name: 'Bram', profession: 'farmer' } }, {
      apiKey: 'k', upstreamUrl: `ws://127.0.0.1:${provider.address().port}`, connect: (url, options) => new WebSocket(url, options),
      cache, now: Date.now, log: () => resolve(shout), onClose: () => {} });
    shout.start();
  });
  const rap = await run('rap');
  assert.match(prompts[0], /^\[A jukebox is playing a lively tune and everyone is dancing\] Break into a short, playful rap: two to four rhyming lines about your life as the village farmer/);
  assert.equal(rap.replay.find(e => e.type === 'line_end').text, 'I farm the wheat, I keep the beat.');
  assert.equal(cache.clips.size, 0, 'raps are not cached');
  await run('bell');
  assert.match(prompts[1], /^\[Someone just rang the village bell and you hurried over to it\]/);
  assert.equal(cache.clips.get('oliver:bell').length, 1, 'bell calls are cached');
});
