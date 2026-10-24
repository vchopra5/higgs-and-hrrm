// Retrieval happens before a spoken PTT response. Nothing in this coordinator
// examines, rewrites, or filters what the villager says.
const MAX_TRACKED_ITEMS = 64;
const MAX_MEMORIES = 32;

export class MemoryContext {
  constructor(session, { transcriptionMs = 3500, recallMs = 2500 } = {}) {
    this.session = session;
    this.transcriptionMs = transcriptionMs;
    this.recallMs = recallMs;
    this.commits = [];
    this.discardedCommits = 0;
    this.items = new Map();
    this.transcripts = new Map();
    this.active = null;
    this.lastTurn = -1;
    this.closed = false;
  }

  get waiting() {
    return this.active !== null;
  }

  get awaitingCommit() { return this.active !== null && this.active.itemId === null; }

  beginTurn(turnId) {
    if (this.closed || this.session.closed || !Number.isSafeInteger(turnId) || turnId <= this.lastTurn) return false;
    this.cancel();
    this.lastTurn = turnId;
    this.commits.push(turnId);
    if (this.commits.length > MAX_TRACKED_ITEMS) {
      this.commits.shift();
      // Preserve acknowledgement order even when very old commits are evicted.
      this.discardedCommits++;
    }
    this.session.preparedMemory = null;
    this.active = { turnId, itemId: null, callId: null, timer: null };
    this.arm(this.transcriptionMs);
    return true;
  }

  committed(itemId) {
    if (this.closed || !validItemId(itemId) || this.items.has(itemId)) return;
    if (this.discardedCommits > 0) {
      this.discardedCommits--;
      boundedSet(this.items, itemId, null);
      this.transcripts.delete(itemId);
      return;
    }
    if (!this.commits.length) return;
    const turnId = this.commits.shift();
    boundedSet(this.items, itemId, turnId);
    const cached = this.transcripts.get(itemId);
    this.transcripts.delete(itemId);
    if (!this.active || this.active.turnId !== turnId) return;
    this.active.itemId = itemId;
    if (cached) this.consume(cached);
  }

  transcript(itemId, text) {
    this.receive(itemId, { text: typeof text === 'string' ? text.slice(0, 4000) : '' });
  }

  failed(itemId) {
    this.receive(itemId, { failed: true });
  }

  receive(itemId, transcript) {
    if (this.closed || !this.active || !validItemId(itemId)) return;
    if (this.items.has(itemId)) {
      if (this.active.itemId === itemId) this.consume(transcript);
      return;
    }
    // Transcription can arrive before the commit acknowledgement. Keep only the
    // first event per item; duplicate events cannot restart either deadline.
    if (!this.transcripts.has(itemId)) boundedSet(this.transcripts, itemId, transcript);
  }

  consume(transcript) {
    if (!this.active || this.active.callId) return;
    const query = typeof transcript.text === 'string' ? transcript.text.slice(0, 4000) : '';
    if (transcript.failed || !query.trim()) {
      this.finish('unavailable');
      return;
    }
    const { session } = this;
    const callId = `memory-recall-${session.id}-${this.active.turnId}`;
    this.active.callId = callId;
    session.pending.set(callId, {
      callId,
      name: '_recall_turn',
      arguments: { query },
      internal: true,
      memoryContext: true,
      turnId: this.active.turnId,
      created: session.now(),
    });
    this.arm(this.recallMs);
  }

  acceptResult(tool, result) {
    if (this.closed || !this.active || !tool?.memoryContext
        || tool.callId !== this.active.callId || tool.turnId !== this.active.turnId) return false;
    if (result && !result.error && Array.isArray(result.memories)) {
      this.finish('ready', result.memories);
    } else this.finish('unavailable');
    return true;
  }

  arm(duration) {
    clearTimeout(this.active.timer);
    const current = this.active;
    current.timer = setTimeout(() => {
      if (this.active !== current) return;
      if (this.awaitingCommit && this.session.failMemoryCommit) {
        // Without an acknowledgement there is no safely identified user turn.
        // Reconnecting also prevents a rejected commit shifting the FIFO forever.
        this.cancel();
        this.session.failMemoryCommit();
      } else this.finish('unavailable');
    }, duration);
    current.timer.unref?.();
  }

  finish(status, memories) {
    if (!this.active || this.closed) return;
    this.cancel();
    if (this.session.closed) return;
    const fallback = this.session.context?.memories;
    this.session.preparedMemory = {
      memories: (Array.isArray(memories) ? memories : Array.isArray(fallback) ? fallback : []).slice(0, MAX_MEMORIES),
      status,
      asOf: this.session.now(),
    };
    this.session.flushPreparedResponse();
  }

  cancel() {
    if (this.active) {
      clearTimeout(this.active.timer);
      if (this.active.callId) this.session.pending.delete(this.active.callId);
      this.active = null;
    }
    this.transcripts.clear();
  }

  close() {
    this.cancel();
    this.closed = true;
    this.commits.length = 0;
    this.discardedCommits = 0;
    this.items.clear();
  }
}

function validItemId(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 200;
}

function boundedSet(map, key, value) {
  map.set(key, value);
  if (map.size > MAX_TRACKED_ITEMS) map.delete(map.keys().next().value);
}
