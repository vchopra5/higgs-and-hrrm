import { randomBytes, randomUUID } from 'node:crypto';
import { WebSocket } from 'ws';
import { ambientCharacter } from './personalities.js';
import { LIMITS, sendJson } from './protocol.js';

// A villager screaming as a zombie chases it. One line in the villager's own voice, played from the villager for
// nearby players through the chatter listen channel. Screams are cached per voice, so after the first few they cost
// nothing and play at once.
export const SHOUT_LIMITS = Object.freeze({ generating: 2, variants: 3, clipBytes: 400_000, retainMs: 15_000, shoutMs: 20_000 });
export const SHOUT_KINDS = new Set(['zombie']);

const PROMPTS = {
  zombie: '[A zombie is chasing you right now] Scream for help in terror: a cry and at most a few panicked words, such as '
    + 'calling out that a zombie is after you. No narration, no description of sounds.',
};

const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max
  && !/[\u0000-\u001f\u007f-\u009f]/u.test(value);

export function parseShout(body) {
  const speaker = body?.speaker;
  if (!SHOUT_KINDS.has(body?.kind) || !speaker || typeof speaker !== 'object' || !Number.isInteger(speaker.entityId)
      || !text(speaker.name, 40) || !text(speaker.profession, 32)) throw new Error('Invalid shout');
  return { kind: body.kind, speaker };
}

function deliver(client, event) {
  if (client.readyState !== WebSocket.OPEN) return;
  if (client.bufferedAmount > SHOUT_LIMITS.clipBytes * 4) { client.close(1013, 'Listener too slow'); return; }
  client.send(JSON.stringify(event));
}

/** Cached screams per voice and kind: lists of audio chunks plus the transcript. */
export class ShoutCache {
  constructor(random = Math.random) { this.clips = new Map(); this.random = random; }
  key(voice, kind) { return `${voice}:${kind}`; }
  /** A cached clip, preferring reuse once a voice has a few variants; null means generate a new one. */
  pick(voice, kind, cachedOnly = false) {
    const clips = this.clips.get(this.key(voice, kind)) ?? [];
    if (!clips.length || (!cachedOnly && clips.length < SHOUT_LIMITS.variants && this.random() < 0.5)) return null;
    return clips[Math.floor(this.random() * clips.length)];
  }
  store(voice, kind, clip) {
    const key = this.key(voice, kind);
    const clips = this.clips.get(key) ?? [];
    if (clips.length < SHOUT_LIMITS.variants) clips.push(clip);
    this.clips.set(key, clips);
  }
}

export class ShoutScene {
  constructor(body, { apiKey, upstreamUrl, connect, now, log, onClose, cache, voiceOverrides = {}, cachedOnly = false }) {
    this.cachedOnly = cachedOnly;
    const { kind, speaker } = parseShout(body);
    this.kind = kind;
    this.speaker = speaker;
    this.id = randomUUID();
    this.token = randomBytes(32).toString('hex');
    this.apiKey = apiKey;
    this.upstreamUrl = upstreamUrl;
    this.connect = connect;
    this.now = now;
    this.log = log;
    this.onClose = onClose;
    this.cache = cache;
    this.character = ambientCharacter(speaker.personality, voiceOverrides);
    this.voice = this.character?.voice ?? 'oliver';
    this.listeners = new Set();
    this.replay = [];
    this.audio = [];
    this.audioBytes = 0;
    this.lineText = '';
    this.finished = false;
    this.closed = false;
    this.generating = false;
  }

  start() {
    this.broadcast({ type: 'line', index: 0, speaker: this.speaker.entityId, name: this.speaker.name });
    const cached = this.cache.pick(this.voice, this.kind, this.cachedOnly);
    if (cached) {
      for (const audio of cached.audio) this.broadcast({ type: 'audio', index: 0, audio });
      this.broadcast({ type: 'line_end', index: 0, text: cached.text });
      this.finish('cached');
      return;
    }
    this.generating = true;
    this.timer = setTimeout(() => this.finish('time_limit'), SHOUT_LIMITS.shoutMs);
    this.timer.unref?.();
    try {
      this.upstream = this.connect(this.upstreamUrl, {
        headers: { Authorization: `Bearer ${this.apiKey}` }, maxPayload: LIMITS.messageBytes, handshakeTimeout: LIMITS.connectionMs,
      });
    } catch {
      this.finish('upstream_connect_failed');
      return;
    }
    this.upstream.on('open', () => this.configure());
    this.upstream.on('message', data => this.receive(data));
    this.upstream.on('error', () => this.finish('upstream_error'));
    this.upstream.on('close', () => this.finish('upstream_closed'));
  }

  configure() {
    this.requested = `You are ${this.speaker.name}, a Minecraft villager who works as a ${this.speaker.profession}. `
      + 'You react out loud to danger in short, raw, in-character cries. ' + (this.character?.instructions ?? '');
    sendJson(this.upstream, { type: 'session.update', session: {
      model: 'higgs-realtime', instructions: this.requested, output_modalities: ['audio'],
      audio: {
        input: { format: { type: 'audio/pcm', rate: 24000 }, turn_detection: null },
        output: { format: { type: 'audio/pcm', rate: 24000 }, voice: this.voice },
      },
    } });
  }

  receive(data) {
    if (this.finished) return;
    let event;
    try { event = JSON.parse(data); } catch { return; }
    switch (event.type) {
      case 'session.created':
      case 'session.updated':
        if (!this.asked && (event.type === 'session.updated' || event.session?.instructions === this.requested)) {
          this.asked = true;
          sendJson(this.upstream, { type: 'conversation.item.create', item: { type: 'message', role: 'user',
            content: [{ type: 'input_text', text: PROMPTS[this.kind] }] } });
          sendJson(this.upstream, { type: 'response.create' });
        }
        break;
      case 'response.output_audio.delta':
        if (typeof event.delta === 'string') {
          this.audio.push(event.delta);
          this.audioBytes += event.delta.length;
          this.broadcast({ type: 'audio', index: 0, audio: event.delta });
        }
        break;
      case 'response.output_audio_transcript.done':
        if (typeof event.transcript === 'string') this.lineText = event.transcript.slice(0, 200);
        break;
      case 'response.done': {
        const text = this.lineText.trim();
        this.broadcast({ type: 'line_end', index: 0, text });
        if (event.response?.status === 'completed' && this.audio.length && this.audioBytes <= SHOUT_LIMITS.clipBytes)
          this.cache.store(this.voice, this.kind, { audio: this.audio, text });
        this.finish('completed');
        break;
      }
      case 'error':
        this.finish('provider_error');
        break;
    }
  }

  broadcast(event) {
    this.replay.push(event);
    for (const listener of this.listeners) deliver(listener, event);
  }

  attach(client) {
    if (this.closed) { client.close(1013, 'Shout unavailable'); return; }
    for (const event of this.replay) deliver(client, event);
    if (this.finished) { client.close(1000, 'shout_finished'); return; }
    this.listeners.add(client);
    client.on('close', () => this.listeners.delete(client));
    client.on('error', () => this.listeners.delete(client));
    client.on('message', () => {});
  }

  finish(reason) {
    if (this.finished) return;
    this.finished = true;
    this.generating = false;
    clearTimeout(this.timer);
    this.broadcast({ type: 'end', reason: reason === 'closed' ? 'closed' : 'completed' });
    this.log({ event: 'shout_finished', shoutId: this.id, kind: this.kind, reason });
    if (this.upstream?.readyState === WebSocket.OPEN) this.upstream.close(1000, reason);
    else if (this.upstream?.readyState === WebSocket.CONNECTING) this.upstream.terminate();
    for (const listener of this.listeners) listener.close(1000, reason);
    this.listeners.clear();
    this.retain = setTimeout(() => this.close(), SHOUT_LIMITS.retainMs);
    this.retain.unref?.();
  }

  close() {
    if (this.closed) return;
    if (!this.finished) this.finish('closed');
    this.closed = true;
    clearTimeout(this.retain);
    this.replay = [];
    this.onClose(this.id);
  }
}
