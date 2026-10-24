import test from 'node:test';
import assert from 'node:assert/strict';
import { repeatedReaction, reactionSimilarity } from '../lib/reaction-variety.js';
test('reaction variety rejects repeated wording and cosmetic changes', () => {
  const history = ['Ow! Stop hitting me!', 'Keep your hands to yourself.'];
  assert.equal(repeatedReaction('Hey, stop hitting me.', history), true);
  assert.equal(repeatedReaction('Please keep your hands to yourself!', history), true);
  assert.equal(repeatedReaction('Was that really necessary?', history), false);
  assert.equal(repeatedReaction('Ow!', ['Ouch!']), true);
  assert.equal(repeatedReaction('Ow!', []), false);
});

test('cosine threshold is strictly greater than 0.7', () => {
  const a = 'one two three four five six seven eight nine ten';
  const atThreshold = 'one two three four five six seven eleven twelve thirteen';
  const above = 'one two three four five six seven eight twelve thirteen';
  assert.equal(reactionSimilarity(a, atThreshold), 0.7);
  assert.equal(repeatedReaction(a, [atThreshold]), false);
  assert.equal(repeatedReaction(a, [above]), true);
  assert.equal(reactionSimilarity('stop stop now', 'stop now'), 3 / Math.sqrt(10));
});
