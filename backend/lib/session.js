import { randomBytes, randomUUID } from 'node:crypto';
import { WebSocket } from 'ws';
import { LIMITS, sanitizeCode, sendJson, sessionConfig } from './protocol.js';
import {
  receiveClientAudioEvent, receiveSpeechStarted, receiveSpeechStopped, relayProviderAudio, setMuted,
} from './audio.js';
import { completeToolBatch, expireTools, receiveToolCall } from './tools.js';

const DURATION_MESSAGE = 'The ten-minute conversation limit was reached. '
  + 'Start a new conversation to continue.';
const RESPONSE_STATUSES = new Set(['completed', 'cancelled', 'failed', 'incomplete']);

export class BridgeSession {
  constructor(body, { apiKey, upstreamUrl, connect, now, log, onClose }) {
    this.id = randomUUID();
    this.ticket = randomBytes(32).toString('hex');
    this.created = now();
    this.playerId = body.playerId;
    this.context = body.context;
    this.apiKey = apiKey;
    this.upstreamUrl = upstreamUrl;
    this.connect = connect;
    this.now = now;
    this.log = log;
    this.onClose = onClose;
    this.closed = false;
    this.ready = false;
    this.configSent = false;
    this.recording = false;
    this.cancelled = false;
    this.muted = false;
    this.firstAudioSent = false;
    this.responseId = null;
    this.speechStoppedAt = null;
    this.pending = new Map();
    this.seen = new Set();
    this.responseDone = true;
    this.hasTools = false;
    this.inputBytes = 0;
    this.diagnostics = {
      inputBytes: 0, outputBytes: 0,
      speechStarts: 0, speechStops: 0, responses: 0,
      firstAudioLatencyMs: null, lastEvent: 'session_allocated', lastError: null,
      suppressedAudio: { speaking: 0, cancelled: 0, staleResponse: 0, backpressure: 0 },
    };
    this.timer = setTimeout(() => {
      this.sendError(true, 'session_duration_limit', DURATION_MESSAGE);
      this.close('session_duration_limit');
    }, LIMITS.sessionMs);
    this.timer.unref();
  }

  sendClient(event) { return sendJson(this.client, event); }
  sendProvider(event) { return sendJson(this.upstream, event); }

  sendError(fatal, code, message) {
    this.sendClient({ type: 'error', fatal, code, message });
  }

  stage(stage, extra = {}) {
    this.log({
      event: 'voice_stage', sessionId: this.id, stage,
      elapsedMs: this.now() - this.created, ...extra,
    });
  }

  isStaleResponse(responseId) {
    return Boolean(responseId && this.responseId && responseId !== this.responseId);
  }

  attach(client, mode) {
    this.client = client;
    this.mode = mode;
    client.on('error', () => this.close('client_socket_error'));
    client.on('close', code => this.close('client_closed', code));
    client.on('message', (data, binary) => this.receiveClientMessage(data, binary));
    this.sendClient({ type: 'status', message: 'Connecting to Higgs' });
    this.connectProvider();
  }

  connectProvider() {
    try {
      this.upstream = this.connect(this.upstreamUrl, {
        headers: { Authorization: `Bearer ${this.apiKey}` },
        maxPayload: LIMITS.messageBytes,
        handshakeTimeout: LIMITS.connectionMs,
      });
    } catch {
      this.sendError(true, 'upstream_connection_failed', 'Cannot connect to Higgs');
      this.close('upstream_connect_failed');
      return;
    }

    this.connectTimer = setTimeout(() => {
      this.sendError(true, 'upstream_timeout', 'Higgs connection timed out');
      this.close('upstream_timeout');
    }, LIMITS.connectionMs);
    this.connectTimer.unref();

    this.upstream.on('open', () => {
      const config = sessionConfig(this.context, this.mode);
      this.requestedInstructions = config.session.instructions;
      this.configSent = this.sendProvider(config);
    });
    this.upstream.on('message', data => this.receiveProviderMessage(data));
    this.upstream.on('error', () => {
      this.sendError(true, 'upstream_connection_failed',
        'Higgs connection failed; check backend API configuration');
      this.close('upstream_socket_error');
    });
    this.upstream.on('close', code => {
      if (this.closed) return;
      this.sendError(true, 'upstream_closed',
        `Higgs connection closed (code ${code}). Start a new conversation to reconnect.`);
      this.close('upstream_closed', code);
    });
  }

  receiveClientMessage(data, binary) {
    if (this.closed) return;
    try {
      if (binary) throw new Error('Expected JSON');
      const event = JSON.parse(data);
      if (event.type === 'end') {
        this.close('client_requested_end');
      } else if (event.type === 'mute') {
        setMuted(this, event.muted);
      } else if (!this.ready) {
        this.sendClient({ type: 'status', message: 'Wait for Ready before speaking' });
      } else {
        receiveClientAudioEvent(this, event);
      }
    } catch (error) {
      this.sendError(false, 'invalid_client_message', error.message);
    }
  }

  receiveProviderMessage(data) {
    if (this.closed) return;
    try {
      const event = JSON.parse(data);
      this.diagnostics.lastEvent = sanitizeCode(event.type ?? 'unknown');
      switch (event.type) {
        case 'session.created':
        case 'session.updated':
          this.markReady(event);
          break;
        case 'response.output_audio.delta':
          relayProviderAudio(this, event);
          break;
        case 'response.created':
          this.startResponse(event.response?.id);
          break;
        case 'input_audio_buffer.speech_started':
          receiveSpeechStarted(this);
          break;
        case 'input_audio_buffer.speech_stopped':
          receiveSpeechStopped(this);
          break;
        case 'response.function_call_arguments.done':
          receiveToolCall(this, event);
          break;
        case 'response.done':
          this.finishResponse(event.response);
          break;
        case 'error':
          this.receiveProviderError(event.error);
          break;
        case 'session.idle_timeout':
          this.sendClient({ type: 'status', message: 'Higgs ended the session after prolonged silence.' });
          break;
        case 'session.max_duration_reached':
          this.sendClient({ type: 'status', message: 'Higgs session duration limit reached.' });
          break;
      }
    } catch {
      this.sendError(false, 'invalid_provider_response', 'Invalid Higgs response');
    }
  }

  markReady(event) {
    if (!this.configSent || this.ready) return;
    // A provider's initial session.created may precede application of our prompt.
    // Autonomous speech must wait for the configured session, never the default persona.
    if (this.context.autoGreeting === true && event.type !== 'session.updated'
        && event.session?.instructions !== this.requestedInstructions) return;
    clearTimeout(this.connectTimer);
    this.ready = true;
    this.stage('upstream_ready');
    this.sendClient({ type: 'status', message: 'Ready' });
    if (this.context.autoGreeting === true) {
      this.responseDone = false;
      this.sendProvider({ type: 'response.create' });
    }
  }

  startResponse(responseId) {
    this.responseId = responseId;
    this.responseDone = false;
    this.cancelled = false;
    this.firstAudioSent = false;
    this.diagnostics.responses++;
    this.diagnostics.firstAudioLatencyMs = null;
    this.stage('response_created');
  }

  finishResponse(response) {
    if (this.isStaleResponse(response?.id)) return;
    this.responseDone = true;
    const status = RESPONSE_STATUSES.has(response?.status) ? response.status : 'unknown';
    this.stage('response_done', { status });
    if (response?.status === 'cancelled') this.cancelled = true;
    completeToolBatch(this);
  }

  receiveProviderError(error) {
    const code = sanitizeCode(error?.code ?? error?.type ?? 'provider_error');
    this.diagnostics.lastError = code;
    this.log({ event: 'provider_error', sessionId: this.id, code });
    const message = this.ready
      ? `Higgs reported ${code}; the conversation is still connected.`
      : `Higgs rejected session setup (${code}). Check the API configuration.`;
    this.sendError(!this.ready, code, message);
    if (!this.ready) this.close('provider_setup_rejected');
  }

  sweep() {
    if (!this.client && this.now() - this.created > LIMITS.ticketMs) {
      this.close('ticket_expired');
      return;
    }
    this.sendClient({
      type: 'diagnostics', ...this.diagnostics,
      pendingTools: this.pending.size,
      upstreamReady: Boolean(this.ready && this.upstream?.readyState === WebSocket.OPEN),
      speaking: Boolean(this.recording), cancelled: Boolean(this.cancelled),
    });
    expireTools(this);
  }

  close(reason = 'session_ended', code = 1000) {
    if (this.closed) return;
    this.closed = true;
    this.onClose(this.id);
    this.log({ event: 'session_closed', sessionId: this.id, reason, code });
    this.sendClient({
      type: 'end', code: reason,
      message: reason === 'session_duration_limit'
        ? DURATION_MESSAGE : `Conversation ended: ${reason.replaceAll('_', ' ')}.`,
    });
    clearTimeout(this.timer);
    clearTimeout(this.connectTimer);
    for (const socket of [this.client, this.upstream]) {
      if (socket?.readyState === WebSocket.OPEN) socket.close(1000, reason);
      else if (socket?.readyState === WebSocket.CONNECTING) socket.terminate();
    }
  }
}
