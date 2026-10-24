import test from 'node:test';
import assert from 'node:assert/strict';
import { ConversationFloor } from '../lib/conversation-floor.js';

function fixture(t) {
  const events = [], heard = [], idle = [];
  const floor = new ConversationFloor(e => { events.push(e); return true; }, (...v) => heard.push(v), (...v) => idle.push(v));
  t.after(() => floor.interrupt());
  return { floor, events, heard, idle };
}
test('generation completion retains floor until matching playback acknowledgement', t => {
  const { floor, events, heard } = fixture(t);
  floor.acquire('main');
  floor.audio('main', { type: 'audio', audio: 'AAAA' });
  floor.line('main', 'Idris, what do you think?');
  const id = floor.active.id;
  floor.acknowledge(id); // Premature acknowledgement cannot release generation.
  assert.ok(floor.active);
  floor.finish('main');
  assert.equal(floor.acquire('other'), false);
  assert.equal(heard.length, 0);
  floor.acknowledge(id + 1);
  assert.ok(floor.active);
  assert.equal(events.at(-1).type, 'playback_end');
  floor.acknowledge(id);
  assert.equal(floor.active, null);
  assert.deepEqual(heard[0], ['main', 'Idris, what do you think?', false]);
});
test('interrupt clears queued responses and records incomplete speech without unheard text', t => {
  const { floor, heard } = fixture(t);
  floor.acquire('main');
  const stale = floor.active.id;
  floor.audio('main', { audio: 'AAAA' });
  floor.line('main', 'Secret not yet heard');
  let calls = 0;
  floor.request('main', () => { calls++; return true; });
  floor.interrupt();
  assert.equal(calls, 0);
  assert.deepEqual(heard[0], ['main', '', true]);
  floor.acquire('other');
  floor.finish('other');
  floor.acknowledge(stale);
  assert.equal(calls, 0);
});
test('tool continuation waits for playback and prevents an unsolicited followup', t => {
  const { floor, idle } = fixture(t);
  floor.acquire('main');
  const id = floor.active.id;
  floor.audio('main', { audio: 'AAAA' });
  let called = false;
  floor.request('main', () => { called = true; return true; });
  floor.finish('main');
  assert.equal(called, false);
  floor.acknowledge(id);
  assert.equal(called, true);
  assert.equal(idle.length, 0);
  assert.notEqual(floor.active.id, id);
});
test('silent responses release immediately and wrong speakers cannot emit audio', t => {
  const { floor, events } = fixture(t);
  floor.acquire('main');
  assert.equal(floor.audio('other', { audio: 'AAAA' }), false);
  floor.line('main', 'Unspoken words');
  floor.finish('main');
  assert.equal(events.length, 0);
  assert.equal(floor.active, null);
});

test('missing playback acknowledgement times out without publishing unheard speech', async t => {
  let expired = false;
  const heard = [];
  const floor = new ConversationFloor(() => true, (...v) => heard.push(v), () => {}, 10, () => { expired = true; });
  t.after(() => floor.interrupt());
  floor.acquire('main');
  floor.audio('main', { audio: 'AAAA' });
  floor.line('main', 'Never confirmed');
  floor.finish('main');
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(expired, true);
  assert.equal(floor.active, null);
  assert.deepEqual(heard[0], ['main', '', true]);
});
