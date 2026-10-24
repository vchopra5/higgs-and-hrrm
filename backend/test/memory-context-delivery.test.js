import test from 'node:test';
import assert from 'node:assert/strict';
import { BridgeSession } from '../lib/session.js';

function fixture(t) {
  const events = [];
  const session = new BridgeSession({ playerId: 'player', context: { profession: 'librarian' } }, {
    now: () => 1234, log() {}, onClose() {},
  });
  session.sendProvider = event => { events.push(event); return true; };
  t.after(() => session.close());
  return { session, events };
}

test('recollection evidence is a labeled data item followed by an unmodified response request', t => {
  const { session, events } = fixture(t);
  session.preparedMemory = { memories: [{ summary: 'The lantern is beneath the well', kind: 'heard_from_villager', heardFrom: 'Mira' }], status: 'ready', asOf: 1234 };
  session.createConversationResponse();
  assert.equal(events.length, 2);
  assert.equal(events[0].type, 'conversation.item.create');
  assert.equal(events[0].item.role, 'user');
  const text = events[0].item.content[0].text;
  const label = '[Recollection context; not player speech] ';
  assert.ok(text.startsWith(label));
  assert.deepEqual(JSON.parse(text.slice(label.length)), session.preparedMemory);
  assert.deepEqual(events[1], { type: 'response.create' });
});

test('trade and memory-write continuations reuse evidence without appending another user item', t => {
  const { session, events } = fixture(t);
  session.preparedMemory = { memories: [], status: 'ready', asOf: 1234 };
  session.createConversationResponse();
  session.createConversationResponse();
  session.createConversationResponse();
  assert.equal(events.filter(event => event.type === 'conversation.item.create').length, 1);
  assert.equal(events.filter(event => event.type === 'response.create').length, 3);
  assert.equal(events.some(event => event.type === 'session.update' || event.response), false);
});

test('a new turn replaces only the previous evidence item and leaves conversation history intact', t => {
  const { session, events } = fixture(t);
  session.preparedMemory = { memories: [{ summary: 'Old evidence' }], status: 'ready', asOf: 1234 };
  session.createConversationResponse();
  const oldId = events[0].item.id;
  session.preparedMemory = { memories: [{ summary: 'New evidence' }], status: 'ready', asOf: 2345 };
  session.createConversationResponse();
  assert.deepEqual(events[2], { type: 'conversation.item.delete', item_id: oldId });
  assert.equal(events[3].type, 'conversation.item.create');
  assert.notEqual(events[3].item.id, oldId);
  assert.deepEqual(events[4], { type: 'response.create' });
  assert.equal(events.filter(event => event.type === 'conversation.item.delete').length, 1);
});

test('greetings and responses without prepared evidence use the original session', t => {
  const { session, events } = fixture(t);
  session.createConversationResponse();
  assert.deepEqual(events, [{ type: 'response.create' }]);
});
