import test from 'node:test';
import assert from 'node:assert/strict';
import { sessionConfig } from '../lib/protocol.js';
import { companionConfig, addressedTo } from '../lib/companion.js';
import { consolidationPrompt } from '../lib/consolidate.js';
import { classifyTreatment } from '../lib/treatment.js';

// These check the application contract, not whether a live model obeys it.
test('automatic transcription and language policy preserve voices, modes and trade tools', () => {
  for (const mode of ['vad', 'ptt']) {
    const context = { personality: { catalogueVersion: 2, archetypeId: 'rook', styleId: 'clipped', familiarity: 0 } };
    const { session } = sessionConfig(context, mode);
    assert.deepEqual(session.audio.input.transcription, { model: 'higgs-stt-3.1' });
    assert.equal(session.audio.output.voice, 'marcus');
    assert.equal(session.audio.input.turn_detection === null, mode === 'ptt');
    assert.match(session.instructions, /follow the language the player is speaking/);
    assert.match(session.instructions, /short yes.*does not change the reply language/);
    assert.match(session.instructions, /IDs and item identifiers unchanged/);
    assert.match(session.instructions, /call recall_memories with concise English search terms/);
    assert.match(session.tools.find(t => t.name === 'confirm_trade').description, /any language/);
    assert.deepEqual(session.tools.find(t => t.name === 'confirm_trade').parameters.required, ['proposalId']);
  }
  assert.equal(sessionConfig({ trade: { canTrade: false } }).session.tools.some(t => t.name === 'confirm_trade'), false);
});

test('companions keep their voice and follow the player language without gaining trade tools', () => {
  const { session } = companionConfig({ name: 'Rook', profession: 'farmer', personality: {
    catalogueVersion: 2, archetypeId: 'rook', styleId: 'clipped', familiarity: 0,
  } }, { name: 'Mira', profession: 'farmer' }, 'Alex');
  assert.equal(session.audio.output.voice, 'marcus');
  assert.match(session.instructions, /follow the language the player is speaking/);
  assert.equal(session.tools, undefined);
  for (const sentence of ['Rook, ¿qué piensas?', 'Rook, तुम क्या सोचते हो?', 'Rook, 你觉得呢？'])
    assert.equal(addressedTo(sentence, 'Rook', 'Mira'), true);
});

test('non-English and mixed-script treatment is left to semantic model classification', () => {
  for (const sentence of ['Das Kind spielt.', 'un kind', 'Tu es formidable', 'No eres nice',
    'Du bist ein Idiot', 'तुम stupid नहीं हो', '你不是 stupid', 'Merci, très nice', 'ありがとう'])
    assert.equal(classifyTreatment(sentence), 'neutral', sentence);
  assert.equal(classifyTreatment('You are not stupid.'), 'neutral');
  assert.equal(classifyTreatment('You are wonderful.'), 'polite');
  assert.equal(classifyTreatment('You are an idiot.'), 'rude');
});

test('multilingual transcripts stay intact while digests request a common retrieval language', () => {
  const transcript = ['Alex: 我没有偷钻石。', 'Mira: Entendido.', 'Alex: मैं कल वापस आऊँगा।'];
  const prompt = consolidationPrompt({ villagerName: 'Mira', playerName: 'Alex', transcript });
  for (const line of transcript) assert.ok(prompt.includes(line));
  assert.match(prompt, /Write digest, summary and promise descriptions in English/);
  assert.match(prompt, /negation, uncertainty and who said what/);
});
