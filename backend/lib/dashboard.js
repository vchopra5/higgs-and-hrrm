import { randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { readJsonBody, replyJson } from './protocol.js';

// A browser dashboard of the villages: the game server syncs a snapshot every few seconds (only while someone is
// watching) and collects the edits made here; live conversations come straight from the bridge's sessions.
// Browser access needs HIGGS_DASHBOARD_TOKEN and a local (loopback) connection.
export const DASHBOARD_LIMITS = Object.freeze({ snapshotBytes: 4 * 1024 * 1024, commands: 50, watchMs: 30_000, staleMs: 15_000, results: 100 });

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const id = value => typeof value === 'string' && UUID.test(value);
const text = (value, min, max) => typeof value === 'string' && value.trim().length >= min && value.length <= max
  && !/[\u0000-\u001f\u007f-\u009f]/u.test(value);

/** Each edit type and what it needs; anything else is rejected before it reaches the game server. */
const EDITS = {
  set_standing: edit => id(edit.villagerId) && id(edit.playerId) && Number.isInteger(edit.value) && edit.value >= -5 && edit.value <= 5,
  clear_quest: edit => id(edit.villagerId) && id(edit.playerId),
  deliver_quest: edit => id(edit.villagerId) && id(edit.playerId),
  clear_errand: edit => id(edit.playerId),
  set_nickname: edit => id(edit.villagerId) && id(edit.playerId) && (edit.nickname === null || text(edit.nickname, 0, 24)),
  reset_relationship: edit => id(edit.villagerId) && id(edit.playerId),
  forget_episode: edit => id(edit.episodeId),
  restock: edit => id(edit.villagerId),
  set_ai: edit => id(edit.villagerId) && typeof edit.enabled === 'boolean',
  rename: edit => id(edit.villagerId) && text(edit.name, 1, 32),
};
const FIELDS = ['villagerId', 'playerId', 'episodeId', 'value', 'nickname', 'enabled', 'name'];

/** A validated edit with only the fields it uses, or null. */
export function parseEdit(body) {
  if (!body || typeof body !== 'object' || !Object.hasOwn(EDITS, body.type) || !EDITS[body.type](body)) return null;
  const edit = { type: body.type };
  for (const field of FIELDS) if (Object.hasOwn(body, field)) edit[field] = body[field];
  return edit;
}

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);

export function createDashboard({ token, sessions, now = Date.now, log = () => {} }) {
  const enabled = typeof token === 'string' && token.length >= 16;
  const expected = Buffer.from(enabled ? token : '');
  let page;
  let snapshot = null, snapshotAt = 0, lastSent = '';
  let commands = [];
  const results = [];
  const clients = new Set();
  let watchedAt = -Infinity;

  function allowed(request, url) {
    const header = request.headers.authorization ?? '';
    const given = Buffer.from(header.startsWith('Bearer ') ? header.slice(7) : url.searchParams.get('token') ?? '');
    return given.length === expected.length && timingSafeEqual(given, expected);
  }

  /** Live conversations as the bridge sees them: exactly what Boson was given, and what was said. */
  function live() {
    return [...sessions.values()].map(session => ({
      id: session.id, playerId: session.playerId, villagerId: session.villagerId,
      villager: session.context?.name, player: session.context?.playerName, startedAt: session.created,
      turns: session.playerTurns, companion: session.companion?.spec?.name ?? null,
      transcript: session.transcriptLines.slice(-30), context: session.context,
    }));
  }

  function state() {
    return { snapshot, snapshotAt, stale: !snapshot || now() - snapshotAt > DASHBOARD_LIMITS.staleMs, live: live(),
      pending: commands.map(({ id, type }) => ({ id, type })), results: results.slice(-20) };
  }

  function broadcast(event, data) {
    const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const client of clients) client.write(frame);
  }

  /** Game server: hand over the latest snapshot and edit results; take the queued edits. */
  async function sync(request, response) {
    const body = await readJsonBody(request, DASHBOARD_LIMITS.snapshotBytes);
    if (body?.snapshot && typeof body.snapshot === 'object') { snapshot = body.snapshot; snapshotAt = now(); }
    for (const result of Array.isArray(body?.results) ? body.results : []) {
      if (typeof result?.id !== 'string') continue;
      const done = { id: result.id, ok: result.ok === true, message: String(result.message ?? '').slice(0, 200), at: now() };
      results.push(done);
      if (results.length > DASHBOARD_LIMITS.results) results.shift();
      broadcast('result', done);
    }
    const watching = clients.size > 0 || now() - watchedAt <= DASHBOARD_LIMITS.watchMs;
    if (watching) {
      const current = JSON.stringify(state());
      if (current !== lastSent) { lastSent = current; broadcast('state', JSON.parse(current)); }
    }
    const handed = commands;
    commands = [];
    replyJson(response, 200, { watching: enabled && watching, commands: handed });
  }

  /** Browser routes; returns false when the path is not the dashboard's. */
  async function handle(request, response, url) {
    const path = url.pathname;
    if (!path.startsWith('/dashboard') || path === '/dashboard/sync') return false;
    if (!enabled) { replyJson(response, 404, { error: 'Dashboard disabled: set HIGGS_DASHBOARD_TOKEN' }); return true; }
    if (!LOOPBACK.has(request.socket.remoteAddress)) { replyJson(response, 403, { error: 'Dashboard is local only' }); return true; }
    // The page itself holds no data (it keeps the token in the tab after the first visit); everything else needs the token.
    const shell = request.method === 'GET' && (path === '/dashboard' || path === '/dashboard/');
    if (!shell && !allowed(request, url)) { replyJson(response, 401, { error: 'Unauthorized' }); return true; }
    if (shell) {
      page ??= readFileSync(new URL('../public/dashboard.html', import.meta.url));
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
        'Content-Security-Policy': "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'" });
      response.end(page);
      return true;
    }
    if (request.method === 'GET' && path === '/dashboard/state') { watchedAt = now(); replyJson(response, 200, state()); return true; }
    if (request.method === 'GET' && path === '/dashboard/events') {
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
      response.write(`event: state\ndata: ${JSON.stringify(state())}\n\n`);
      clients.add(response);
      watchedAt = now();
      request.on('close', () => clients.delete(response));
      return true;
    }
    if (request.method === 'POST' && path === '/dashboard/edit') {
      const edit = parseEdit(await readJsonBody(request));
      if (!edit) { replyJson(response, 400, { error: 'Invalid edit' }); return true; }
      if (commands.length >= DASHBOARD_LIMITS.commands) { replyJson(response, 429, { error: 'Too many pending edits' }); return true; }
      edit.id = randomUUID();
      commands.push(edit);
      log({ event: 'dashboard_edit', type: edit.type, id: edit.id });
      replyJson(response, 202, { id: edit.id });
      return true;
    }
    replyJson(response, 404, { error: 'Not found' });
    return true;
  }

  function close() {
    for (const client of clients) client.end();
    clients.clear();
  }

  return { handle, sync, close, enabled };
}
