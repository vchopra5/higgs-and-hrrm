import { LIMITS } from './protocol.js';
import { interruptTools } from './tools.js';

export function interruptAudio(session, cancelProvider) {
  session.recording = true;
  session.cancelled = true;
  interruptTools(session);
  if (cancelProvider && !session.responseDone && !session.awaitingCancellation) {
    session.awaitingCancellation = true;
    session.sendProvider({ type: 'response.cancel' });
  }
  session.sendClient({ type: 'clear' });
}

export function setMuted(session, muted) {
  if (typeof muted !== 'boolean') throw new Error('muted must be boolean');
  session.muted = muted;
  session.recording = false;
  session.inputBytes = 0;
  if (session.ready) session.sendProvider({ type: 'input_audio_buffer.clear' });
  session.sendClient({ type: 'speech', active: false });
}

function receiveAudio(session, audio) {
  if (session.muted) return;
  if (typeof audio !== 'string' || !/^[A-Za-z0-9+/]*={0,2}$/.test(audio) || audio.length % 4) {
    throw new Error('Invalid audio');
  }
  const bytes = Buffer.from(audio, 'base64').length;
  const exceedsTurn = session.mode === 'ptt' && session.inputBytes + bytes > LIMITS.maximumTurnBytes;
  if (bytes % 2 || exceedsTurn) {
    throw new Error('Audio turn exceeds 60 seconds or has invalid PCM');
  }
  if (session.mode === 'ptt') {
    session.recording = true;
    session.inputBytes += bytes;
  }
  if (session.sendProvider({ type: 'input_audio_buffer.append', audio })) {
    session.diagnostics.inputBytes += bytes;
  }
}

function commitTurn(session) {
  if (session.mode !== 'ptt') return; // VAD owns commits and responses.
  session.recording = false;
  session.speechStoppedAt = session.now();
  if (session.inputBytes >= LIMITS.minimumTurnBytes) {
    session.turnId++;
    session.sendProvider({ type: 'input_audio_buffer.commit' });
    if (session.awaitingCancellation) session.queuedResponse = true;
    else session.sendProvider({ type: 'response.create' });
  } else {
    session.sendProvider({ type: 'input_audio_buffer.clear' });
  }
  session.inputBytes = 0;
}

export function receiveClientAudioEvent(session, event) {
  switch (event.type) {
    case 'audio':
      receiveAudio(session, event.audio);
      break;
    case 'commit':
      commitTurn(session);
      break;
    case 'interrupt':
      interruptAudio(session, true);
      if (session.mode === 'vad') session.recording = false;
      break;
    default:
      throw new Error('Unknown message type');
  }
}

export function receiveSpeechStarted(session) {
  if (session.mode !== 'vad' || session.muted) return;
  session.diagnostics.speechStarts++;
  session.stage('speech_started');
  interruptAudio(session, false);
  session.sendClient({ type: 'speech', active: true });
}

export function receiveSpeechStopped(session) {
  if (session.mode !== 'vad') return;
  session.turnId++;
  session.recording = false;
  session.speechStoppedAt = session.now();
  session.diagnostics.speechStops++;
  session.stage('speech_stopped');
  session.sendClient({ type: 'speech', active: false });
}

export function relayProviderAudio(session, event) {
  const bytes = typeof event.delta === 'string' ? Buffer.from(event.delta, 'base64').length : 0;
  const reason = session.isStaleResponse(event.response_id) ? 'staleResponse'
    : session.recording ? 'speaking'
      : session.cancelled ? 'cancelled' : null;
  if (reason) {
    session.diagnostics.suppressedAudio[reason] += bytes;
    return;
  }
  if (!session.sendClient({ type: 'audio', audio: event.delta })) {
    session.diagnostics.suppressedAudio.backpressure += bytes;
    return;
  }
  session.diagnostics.outputBytes += bytes;
  if (!session.firstAudioSent) {
    session.firstAudioSent = true;
    session.diagnostics.firstAudioLatencyMs = session.speechStoppedAt == null
      ? null : Math.max(0, session.now() - session.speechStoppedAt);
    session.stage('first_audio', { firstAudioLatencyMs: session.diagnostics.firstAudioLatencyMs });
  }
}
