import test from 'node:test';
import assert from 'node:assert/strict';
import { addressedTo, askedToLeave, callName, leaveChance, parseCompanion } from '../lib/companion.js';

test('the companion is addressed by its call name only', () => {
  assert.equal(callName('Idris 2'), 'Idris');
  assert.equal(addressedTo('Idris, what do you think?', 'Idris 2', 'Iris'), true);
  assert.equal(addressedTo('what do you think, idris', 'Idris 2', 'Iris'), true);
  assert.equal(addressedTo('Iris, how much is bread?', 'Idris 2', 'Iris'), false, 'Iris is not Idris');
  assert.equal(addressedTo('Idris and Iris, both of you!', 'Idris 2', 'Iris'), false, 'naming both goes to the main villager');
  assert.equal(addressedTo('I love bramble jam', 'Bram', 'Iris'), false, 'whole words only');
  assert.equal(addressedTo('', 'Bram', 'Iris'), false);
  // Mentioning the companion is not talking to it.
  assert.equal(addressedTo("Yeah, I mean, I think Iris is right. It's a pretty fair price, but I would like to barter.", 'Iris', 'Cedric'), false);
  assert.equal(addressedTo('I think Iris is right.', 'Iris', 'Cedric'), false, 'a mention');
  assert.equal(addressedTo('Iris told me you make swords', 'Iris', 'Cedric'), false, 'about her, to him');
  assert.equal(addressedTo('Hey Iris what do you think', 'Iris', 'Cedric'), true, 'greeting without a comma');
  assert.equal(addressedTo('And you, Iris?', 'Iris', 'Cedric'), true);
  assert.equal(addressedTo('Thanks for the help. Iris, are you heading home?', 'Iris', 'Cedric'), true, 'a later sentence');
  assert.equal(addressedTo('Iris, is that a fair price?', 'Iris', 'Cedric'), true, 'asked for her opinion');
});

test('companion descriptions are validated', () => {
  assert.equal(parseCompanion(undefined), null);
  assert.equal(parseCompanion({ entityId: 'x', name: 'Bram', profession: 'farmer' }), null);
  assert.equal(parseCompanion({ entityId: 1, name: '', profession: 'farmer' }), null);
  assert.equal(parseCompanion({ entityId: 1, name: 'Bram', profession: 'farmer', facts: { notes: 'x'.repeat(9000) } }), null);
  assert.equal(parseCompanion({ entityId: 1, name: 'Bram', profession: 'farmer' }).name, 'Bram');
});

test('asking the companion to leave, and how likely it is to go', () => {
  assert.equal(askedToLeave('Bram, could you give us a moment?', 'Bram', 'Iris'), true);
  assert.equal(askedToLeave('Would you leave us alone, please?', 'Bram', 'Iris'), true, 'unnamed requests go to the companion');
  assert.equal(askedToLeave('Iris, can we talk in private?', 'Bram', 'Iris'), false, 'asking the main villager is not dismissing Bram');
  assert.equal(askedToLeave('I will leave tomorrow', 'Bram', 'Iris'), false);
  assert.equal(leaveChance('friendly'), 1, 'a villager who likes you always obliges');
  assert.ok(leaveChance('neutral') > leaveChance('annoyed') && leaveChance('annoyed') > 0, 'others may linger');
  assert.equal(leaveChance('hostile'), 0);
});

test('companion rejects failed sends and bounds a provider that never answers', async t => {
  const { Companion } = await import('../lib/companion.js');
  const failures = [];
  const session = { turnId: 3, companionFailed: (who, turn) => failures.push(turn) };
  const make = socket => {
    const c = new Companion(session, { name: 'Bram' }, { partner: { name: 'Iris' }, playerName: 'Steve' });
    c.ready = true; c.upstream = socket;
    return c;
  };
  const failed = make({ readyState: 1, bufferedAmount: 0, send() { throw Error('closed during send'); }, close() {} });
  assert.equal(failed.answer(), false);
  assert.equal(failed.ready, false);
  await Promise.resolve();
  assert.deepEqual(failures, [undefined], 'no accepted turn to replay after a synchronous failure');
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const stalled = make({ readyState: 1, bufferedAmount: 0, send() {}, close() {} });
  assert.equal(stalled.answer(), true);
  t.mock.timers.tick(20_001);
  await Promise.resolve();
  assert.equal(stalled.closed, true);
  assert.equal(failures.at(-1), 3, 'recover the exact turn which received no audio');
});

test('a new companion reply waits for cancellation so late audio cannot answer the wrong turn', async t => {
  const { Companion } = await import('../lib/companion.js');
  const audio = [];
  const session = { turnId: 1, sendClient: e => audio.push(e), companionFailed() {} };
  const c = new Companion(session, { name: 'Bram' }, { partner: { name: 'Iris' }, playerName: 'Steve' });
  t.after(() => c.close());
  c.ready = true;
  c.upstream = { readyState: 1, bufferedAmount: 0, send() {}, close() {} };
  assert.equal(c.answer(), true);
  c.cancel(); // Cancellation before response.created.
  session.turnId++;
  assert.equal(c.answer(), false, 'main villager must answer while cancellation is unresolved');
  c.receive(JSON.stringify({ type: 'response.created', response: { id: 'old' } }));
  c.receive(JSON.stringify({ type: 'response.output_audio.delta', response_id: 'old', delta: 'AAAA' }));
  assert.equal(audio.length, 0);
  c.receive(JSON.stringify({ type: 'response.done', response: { id: 'old', status: 'cancelled' } }));
  assert.equal(c.answer(), true, 'available again after cancellation completes');
});
