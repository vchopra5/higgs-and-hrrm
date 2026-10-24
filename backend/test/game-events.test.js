import test from 'node:test';
import assert from 'node:assert/strict';
import { gameEventCue, parseGameEvent } from '../lib/game-events.js';

test('walking events are validated and become one labeled cue each', () => {
  const scenery = parseGameEvent({ type: 'walk_scenery', details: 'Iris the librarian is reading by her lectern; you have walked 40 blocks together.' });
  assert.match(gameEventCue(scenery), /^\[Game event, not player speech\] You are walking with the player\. Around you: Iris the librarian/);
  assert.match(gameEventCue(scenery), /One short sentence only, in character\. Do not trade or call tools\.$/);
  assert.match(gameEventCue(parseGameEvent({ type: 'walk_boundary', place: 'Kettleby' })), /edge of Kettleby\. Tell the player kindly you will go no further/);
  assert.match(gameEventCue(parseGameEvent({ type: 'walk_lagging', guiding: true })), /"this way!"/);
  assert.match(gameEventCue(parseGameEvent({ type: 'walk_lagging' })), /wait for you/);
  assert.match(gameEventCue(parseGameEvent({ type: 'guide_arrived', place: 'the village bell' })), /led the player to the village bell\. Tell them you are here/);
  assert.match(gameEventCue(parseGameEvent({ type: 'guide_failed', place: 'the library', details: 'about 30 blocks north-east of the player' })),
    /could not find the way to the library\. Apologise, and tell them where it is instead: about 30 blocks north-east/);
  for (const bad of [{ type: 'walk_dance' }, { type: 'walk_scenery' }, { type: 'walk_scenery', details: 'x'.repeat(301) },
    { type: 'walk_boundary', place: 'Kettle\nby' }, { type: 'walk_lagging', guiding: 'yes' }, { type: 'milestone' }]) {
    assert.throws(() => parseGameEvent(bad), JSON.stringify(bad));
  }
});

test('a relationship milestone becomes a warm cue that never names the stage', () => {
  const cue = gameEventCue(parseGameEvent({ type: 'milestone', details: 'a close friend' }));
  assert.match(cue, /You have just come to think of the player as a close friend\. Let it show warmly/);
  assert.match(cue, /without naming any stage or level/);
});

test('arriving out in the wild points it out and heads home', () => {
  const cue = gameEventCue(parseGameEvent({ type: 'expedition_arrived', place: 'a desert temple', details: 'Kettleby' }));
  assert.match(cue, /brought the player out to a desert temple\. Point it out, wish them luck, and tell them you are heading back to Kettleby now\./);
});
