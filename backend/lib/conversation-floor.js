/** A response owns the floor through generation AND client playback. */
export class ConversationFloor {
  constructor(send, heard, idle, timeout = 90000, expired = () => {}) {
    Object.assign(this, { send, heard, idle, timeout, expired });
    this.serial = 0;
    this.active = null;
    this.queued = null;
  }
  acquire(owner) {
    if (this.active) return false;
    this.active = { id: ++this.serial, owner, bytes: 0, lines: [], ended: false };
    this.timer = setTimeout(() => { const owner = this.active?.owner; this.interrupt(); this.expired(owner); }, this.timeout);
    this.timer.unref?.();
    return true;
  }
  request(owner, run) {
    if (!this.acquire(owner)) { this.queued = () => this.request(owner, run); return true; }
    if (run()) return true;
    this.release(false);
    return false;
  }
  audio(owner, event) {
    if (this.active?.owner !== owner || this.active.ended) return false;
    this.active.bytes += Buffer.from(event.audio ?? '', 'base64').length;
    return this.send({ ...event, floorId: this.active.id });
  }
  line(owner, text) {
    if (this.active?.owner === owner) this.active.lines.push(text);
  }
  finish(owner, completed = true) {
    if (this.active?.owner !== owner) return;
    this.active.ended = true;
    if (!completed || !this.active.bytes) return this.release(false);
    this.send({ type: 'playback_end', floorId: this.active.id });
  }
  acknowledge(id) {
    if (this.active?.id === id && this.active.ended) this.release(true);
  }
  release(played, resume = true) {
    const old = this.active;
    clearTimeout(this.timer);
    this.active = null;
    if (old) {
      if (!played && old.bytes) this.send({ type: 'clear' });
      this.heard(old.owner, played ? old.lines.join(' ') : '', !played && old.bytes > 0);
    }
    if (played) old?.onPlayed?.();
    const next = this.queued;
    this.queued = null;
    if (resume) { if (next) next(); else if (old) this.idle(old.owner, played ? old.lines.join(' ') : ''); }
  }
  interrupt() { this.queued = null; this.release(false, false); }
}
