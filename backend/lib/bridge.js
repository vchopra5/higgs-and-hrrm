import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';
import { LIMITS, readJsonBody, replyJson } from './protocol.js';
import { validateVoiceOverrides } from './personalities.js';
import { BridgeSession } from './session.js';
import { submitToolResult } from './tools.js';

export function createBridge({
  apiKey,
  token,
  upstreamUrl = 'wss://api.boson.ai/v1/realtime/',
  connect = (url, options) => new WebSocket(url, options),
  voiceOverrides = {},
  now = Date.now,
  log = entry => console.info(JSON.stringify(entry)),
} = {}) {
  if (typeof token !== 'string' || token.length < 32) {
    throw new Error('Set HIGGS_BRIDGE_TOKEN to a random secret of at least 32 characters.');
  }
  voiceOverrides = validateVoiceOverrides(voiceOverrides);
  const sessions = new Map();
  const expectedAuth = Buffer.from(`Bearer ${token}`);
  const sessionOptions = {
    apiKey, upstreamUrl, connect, now, log, voiceOverrides,
    onClose: id => sessions.delete(id),
  };

  function authorized(request) {
    const received = Buffer.from(request.headers.authorization ?? '');
    return received.length === expectedAuth.length && timingSafeEqual(received, expectedAuth);
  }

  async function createSession(request, response) {
    if (!apiKey) return replyJson(response, 503, { error: 'BOSON_API_KEY is not configured' });
    const body = await readJsonBody(request);
    if (typeof body?.playerId !== 'string' || typeof body.villagerId !== 'string'
        || !body.context || typeof body.context !== 'object') {
      return replyJson(response, 400, { error: 'playerId, villagerId and context required' });
    }
    if (sessions.size >= LIMITS.sessions) {
      return replyJson(response, 429, { error: 'Session limit reached' });
    }
    const session = new BridgeSession(body, sessionOptions);
    for (const session of sessions.values()) {
      if (session.playerId === body.playerId) session.close('replaced_by_new_session');
    }
    sessions.set(session.id, session);
    replyJson(response, 201, { sessionId: session.id, ticket: session.ticket });
  }

  async function handleRequest(request, response) {
    if (!authorized(request)) return replyJson(response, 401, { error: 'Unauthorized' });
    try {
      const path = new URL(request.url, 'http://localhost').pathname;
      if (request.method === 'POST' && path === '/sessions') {
        return await createSession(request, response);
      }
      const match = path.match(/^\/sessions\/([^/]+)(?:\/tools(?:\/([^/]+)\/result)?|\/events)?$/);
      if (!match) return replyJson(response, 404, { error: 'Not found' });
      const session = sessions.get(match[1]);
      if (!session) return replyJson(response, 404, { error: 'Session not found' });

      if (request.method === 'DELETE' && path === `/sessions/${session.id}`) {
        session.close();
        return replyJson(response, 200, { ok: true });
      }
      if (request.method === 'POST' && path.endsWith('/events')) {
        session.environmentEvent(await readJsonBody(request));
        return replyJson(response, 202, { ok: true });
      }
      if (request.method === 'GET' && path.endsWith('/tools')) {
        const pending = [...session.pending.values()].map(({ created, ...tool }) => tool);
        return replyJson(response, 200, pending);
      }
      if (request.method === 'POST' && match[2]) {
        const body = await readJsonBody(request);
        if (!body || !Object.hasOwn(body, 'result')) {
          return replyJson(response, 400, { error: 'result required' });
        }
        const accepted = submitToolResult(session, match[2], body.result);
        return replyJson(response, accepted ? 200 : 404, { ok: true });
      }
      replyJson(response, 405, { error: 'Method not allowed' });
    } catch {
      replyJson(response, 400, { error: 'Invalid request' });
    }
  }

  const server = createServer(handleRequest);
  const wss = new WebSocketServer({ noServer: true, maxPayload: LIMITS.messageBytes });
  server.on('upgrade', (request, socket, head) => {
    let url;
    try {
      url = new URL(request.url, 'http://localhost');
    } catch {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
      return;
    }
    const ticket = url.searchParams.get('ticket');
    const session = [...sessions.values()].find(candidate => candidate.ticket && candidate.ticket === ticket);
    if (url.pathname !== '/audio' || !session || now() - session.created > LIMITS.ticketMs || session.client) {
      socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      return;
    }
    session.ticket = null;
    const mode = url.searchParams.get('mode') === 'ptt' ? 'ptt' : 'vad';
    wss.handleUpgrade(request, socket, head, client => session.attach(client, mode));
  });

  const sweep = setInterval(() => {
    for (const session of sessions.values()) session.sweep();
  }, 1000);
  sweep.unref();

  let closing;
  function close() {
    closing ??= (async () => {
      clearInterval(sweep);
      for (const session of sessions.values()) session.close();
      for (const client of wss.clients) client.terminate();
      wss.close();
      await new Promise(resolve => server.close(resolve));
    })();
    return closing;
  }
  return { server, sessions, close };
}
