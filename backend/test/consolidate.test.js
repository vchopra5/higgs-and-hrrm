import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { createBridge } from '../src.js';
import { consolidate, consolidationPrompt, parseDigest } from '../lib/consolidate.js';

const token = 'e'.repeat(64);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate) {
  for (let attempt = 0; attempt < 300; attempt++) { if (await predicate()) return; await delay(10); }
  throw new Error('Timed out');
}
const IRIS = '11111111-1111-4111-8111-111111111111', BRAM = '22222222-2222-4222-8222-222222222222';

test('digests are validated and bounded', () => {
  const ok = parseDigest('Sure! {"digest":"Steve promised to deal with the wolves.","summary":"Steve is a polite regular.","promises":["Steve promised to deal with the wolves tomorrow."]}');
  assert.deepEqual(ok, { digest: 'Steve promised to deal with the wolves.', summary: 'Steve is a polite regular.', promises: ['Steve promised to deal with the wolves tomorrow.'] });
  assert.equal(parseDigest('{"digest":"","summary":"x","promises":[]}'), null, 'empty digest');
  assert.equal(parseDigest('not json'), null);
  const long = parseDigest(JSON.stringify({ digest: 'a\u0007b', summary: 'x'.repeat(900), promises: Array(9).fill('p') }));
  assert.equal(long.digest, 'a b');
  assert.equal(long.summary.length, 600);
  assert.equal(long.promises.length, 4);
  const prompt = consolidationPrompt({ villagerName: 'Iris', playerName: 'Steve', previousSummary: 'Steve gave me a diamond.', transcript: ['Steve: Hi'] });
  assert.match(prompt, /Your relationship summary so far: "Steve gave me a diamond\."/);
  assert.match(prompt, /a promise or plan is not done \(say "promised to", not "did"\)/);
  assert.match(prompt, /Leave out anything the player asked you to keep secret/);
});

test('consolidation runs one text-only session and parses its JSON', async t => {
  const provider = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await once(provider, 'listening');
  t.after(() => new Promise(resolve => { for (const c of provider.clients) c.terminate(); provider.close(resolve); }));
  const configs = [];
  provider.on('connection', socket => socket.on('message', data => {
    const event = JSON.parse(data);
    if (event.type === 'session.update') { configs.push(event.session); socket.send(JSON.stringify({ type: 'session.created', session: event.session })); }
    if (event.type === 'response.create') {
      socket.send(JSON.stringify({ type: 'response.output_text.delta', delta: '{"digest":"Steve bought two axes.",' }));
      socket.send(JSON.stringify({ type: 'response.output_text.delta', delta: '"summary":"Steve is a regular.","promises":[]}' }));
      socket.send(JSON.stringify({ type: 'response.done', response: { status: 'completed' } }));
    }
  }));
  const result = await consolidate({ apiKey: 'k', upstreamUrl: `ws://127.0.0.1:${provider.address().port}`,
    villagerName: 'Iris', playerName: 'Steve', transcript: ['Steve: Two axes please.', 'Iris: Done, thanks.'] });
  assert.deepEqual(result, { digest: 'Steve bought two axes.', summary: 'Steve is a regular.', promises: [] });
  assert.deepEqual(configs[0].output_modalities, ['text'], 'text only: mixed modalities are rejected by the provider');
  assert.equal(configs[0].tools, undefined);
});

test('a finished conversation is consolidated once and collected by the game server', async t => {
  const calls = [];
  const bridge = createBridge({ token, apiKey: 'k', log: () => {}, consolidate: async args => {
    calls.push(args);
    return { digest: 'Steve promised to deal with the wolves.', summary: 'Steve is a regular who keeps his word.', promises: ['Steve promised to deal with the wolves.'] };
  } });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  t.after(() => bridge.close());
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const request = (path, method = 'GET', body) => fetch(base + path, { method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) });
  const start = async () => (await (await request('/sessions', 'POST', { playerId: 'steve', villagerId: IRIS,
    context: { name: 'Iris', playerName: 'Steve', relationship: { summary: 'Steve gave me a diamond.' } } })).json()).sessionId;
  // One player turn: not worth consolidating.
  const short = await start();
  bridge.sessions.get(short).playerTurns = 1;
  await request(`/sessions/${short}`, 'DELETE');
  await delay(20);
  assert.equal(calls.length, 0);
  const id = await start();
  const session = bridge.sessions.get(id);
  session.remember('Steve', 'Wolves again? I will deal with them.');
  session.remember('Iris', 'Thank you, traveller.');
  session.playerTurns = 2;
  session.notePresent({ villagerId: BRAM, name: 'Bram' });
  session.notePresent({ villagerId: 'not-a-uuid', name: 'Nobody' });
  await request(`/sessions/${id}`, 'DELETE');
  await until(() => calls.length === 1);
  assert.equal(calls[0].previousSummary, 'Steve gave me a diamond.');
  assert.deepEqual(calls[0].transcript, ['Steve: Wolves again? I will deal with them.', 'Iris: Thank you, traveller.']);
  let collected;
  await until(async () => (collected = await (await request('/digests')).json()).length === 1);
  assert.equal(collected[0].villagerId, IRIS);
  assert.equal(collected[0].playerId, 'steve');
  assert.deepEqual(collected[0].companions, [BRAM]);
  assert.deepEqual(collected[0].promises, ['Steve promised to deal with the wolves.']);
  assert.deepEqual(await (await request('/digests')).json(), [], 'collected once');
  assert.equal((await fetch(base + '/digests')).status, 401, 'game server only');
});

test('consolidation can be turned off', async t => {
  let called = false;
  const bridge = createBridge({ token, apiKey: 'k', log: () => {}, consolidation: false, consolidate: async () => { called = true; return null; } });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  t.after(() => bridge.close());
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const created = await fetch(base + '/sessions', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ playerId: 'p', villagerId: IRIS, context: {} }) });
  const { sessionId } = await created.json();
  bridge.sessions.get(sessionId).playerTurns = 5;
  await fetch(`${base}/sessions/${sessionId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } });
  await delay(20);
  assert.equal(called, false);
});
