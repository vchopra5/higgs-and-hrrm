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
