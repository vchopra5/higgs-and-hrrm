import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyTreatment } from '../lib/treatment.js';

test('compliments, thanks and polite requests are polite', () => {
  assert.equal(classifyTreatment('Your eyes are beautiful.'), 'polite');
  assert.equal(classifyTreatment('Thank you so much!'), 'polite');
  assert.equal(classifyTreatment('Could I have some bread, please?'), 'polite');
  assert.equal(classifyTreatment('You have the BEST shop in the village'), 'polite');
});

test('insults are rude and win over compliments', () => {
  assert.equal(classifyTreatment('You are a stupid villager.'), 'rude');
  assert.equal(classifyTreatment('Shut up and sell me bread.'), 'rude');
  assert.equal(classifyTreatment('Nice hat, you idiot.'), 'rude');
  assert.equal(classifyTreatment('That price is a rip-off'), 'rude');
});

test('ordinary and negated speech is neutral', () => {
  assert.equal(classifyTreatment('What can I buy?'), 'neutral');
  assert.equal(classifyTreatment("You're not stupid."), 'neutral');
  assert.equal(classifyTreatment('That is not very nice'), 'neutral');
  assert.equal(classifyTreatment(''), 'neutral');
  assert.equal(classifyTreatment(undefined), 'neutral');
});
