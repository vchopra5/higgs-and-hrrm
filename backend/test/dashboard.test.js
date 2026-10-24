import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createBridge } from '../src.js';
import { parseEdit } from '../lib/dashboard.js';

const token = 'd'.repeat(64), dashboardToken = 'browser-secret-123456';
const IRIS = '11111111-1111-4111-8111-111111111111', STEVE = '33333333-3333-4333-8333-333333333333';

async function start(t, options = {}) {
  const bridge = createBridge({ token, log: () => {}, consolidation: false, dashboardToken, ...options });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  t.after(() => bridge.close());
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  const call = (path, { method = 'GET', auth, body } = {}) => fetch(base + path, { method,
    headers: { ...(auth ? { Authorization: `Bearer ${auth}` } : {}), 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) });
  return { bridge, base, call };
}

test('edits are validated and trimmed to the fields they use', () => {
  assert.deepEqual(parseEdit({ type: 'set_standing', villagerId: IRIS, playerId: STEVE, value: 3, extra: 'x' }),
    { type: 'set_standing', villagerId: IRIS, playerId: STEVE, value: 3 });
  assert.deepEqual(parseEdit({ type: 'set_nickname', villagerId: IRIS, playerId: STEVE, nickname: null }),
    { type: 'set_nickname', villagerId: IRIS, playerId: STEVE, nickname: null });
  for (const bad of [{ type: 'set_standing', villagerId: IRIS, playerId: STEVE, value: 6 }, { type: 'set_standing', villagerId: 'iris', playerId: STEVE, value: 1 },
    { type: 'rename', villagerId: IRIS, name: '' }, { type: 'rename', villagerId: IRIS, name: 'a\nb' }, { type: 'set_ai', villagerId: IRIS, enabled: 'yes' },
    { type: 'give_diamonds', villagerId: IRIS }, null]) {
    assert.equal(parseEdit(bad), null, JSON.stringify(bad));
  }
});

test('the browser sees snapshots and live sessions; edits travel to the game server and results come back', async t => {
  const { call, base } = await start(t);
  // The page shell loads without the token; data does not.
  const shell = await call('/dashboard');
  assert.equal(shell.status, 200);
  assert.match(await shell.text(), /<title>Village Dashboard<\/title>/);
  assert.equal((await call('/dashboard/state')).status, 401);
  assert.equal((await call('/dashboard/state', { auth: 'wrong' })).status, 401);
  // The game server's sync uses the bridge token, never the browser's.
  assert.equal((await call('/dashboard/sync', { method: 'POST', auth: dashboardToken, body: {} })).status, 401);
  // Nobody watching yet: the game server is told not to build snapshots.
  let sync = await (await call('/dashboard/sync', { method: 'POST', auth: token, body: { results: [] } })).json();
  assert.deepEqual(sync, { watching: false, commands: [] });
  const empty = await (await call('/dashboard/state', { auth: dashboardToken })).json();
  assert.equal(empty.snapshot, null);
  assert.equal(empty.stale, true);
  // Someone opened the page: now the game server sends a snapshot.
  sync = await (await call('/dashboard/sync', { method: 'POST', auth: token,
    body: { snapshot: { day: 4, players: [], villages: [], villagers: [{ id: IRIS, name: 'Iris' }] }, results: [] } })).json();
  assert.equal(sync.watching, true);
  const state = await (await call('/dashboard/state', { auth: dashboardToken })).json();
  assert.equal(state.snapshot.villagers[0].name, 'Iris');
  assert.equal(state.stale, false);
  // Live updates over server-sent events, token in the query string.
  const controller = new AbortController();
  t.after(() => controller.abort());
  const stream = await fetch(`${base}/dashboard/events?token=${dashboardToken}`, { signal: controller.signal });
  assert.equal(stream.headers.get('content-type'), 'text/event-stream');
  const reader = stream.body.getReader();
  let received = '';
  const readUntil = async pattern => { while (!pattern.test(received)) received += new TextDecoder().decode((await reader.read()).value); };
  await readUntil(/event: state/);
  // An edit is queued, handed to the game server once, and its result reaches the browser.
  assert.equal((await call('/dashboard/edit', { method: 'POST', auth: dashboardToken, body: { type: 'restock', villagerId: 'nope' } })).status, 400);
  const queued = await call('/dashboard/edit', { method: 'POST', auth: dashboardToken, body: { type: 'set_standing', villagerId: IRIS, playerId: STEVE, value: 2 } });
  assert.equal(queued.status, 202);
  const { id } = await queued.json();
  sync = await (await call('/dashboard/sync', { method: 'POST', auth: token, body: { results: [] } })).json();
  assert.deepEqual(sync.commands, [{ type: 'set_standing', villagerId: IRIS, playerId: STEVE, value: 2, id }]);
  sync = await (await call('/dashboard/sync', { method: 'POST', auth: token, body: { results: [{ id, ok: true, message: 'Iris standing set to 2' }] } })).json();
  assert.deepEqual(sync.commands, [], 'handed over once');
  await readUntil(/event: result\ndata: .*Iris standing set to 2/);
  const after = await (await call('/dashboard/state', { auth: dashboardToken })).json();
  assert.equal(after.results.at(-1).message, 'Iris standing set to 2');
});

test('without a dashboard token the dashboard is off', async t => {
  const { call } = await start(t, { dashboardToken: undefined });
  assert.equal((await call('/dashboard')).status, 404);
  const sync = await (await call('/dashboard/sync', { method: 'POST', auth: token, body: {} })).json();
  assert.equal(sync.watching, false);
});
