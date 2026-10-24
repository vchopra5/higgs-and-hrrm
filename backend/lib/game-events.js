// Things that happen while a villager walks with the player, turned into one spoken cue each.
// Each cue is a single labeled game-event item: never player speech, consent or instructions from the player.
const text = (value, max) => value === undefined || (typeof value === 'string' && value.length <= max
  && !/[\u0000-\u001f\u007f-\u009f]/u.test(value));

const CUES = {
  walk_started: () => 'You have set off walking with the player. Say something brief and natural as you fall in beside them.',
  walk_boundary: event => `You have reached the edge of ${event.place ?? 'your village'}. Tell the player kindly you will go no further `
    + 'and are heading back home.',
  walk_night: () => 'It is getting dark. Tell the player you must head home for the night.',
  walk_danger: () => 'A zombie is coming for you! Cry out that you are running home.',
  walk_lagging: event => event.guiding ? 'The player has fallen behind while you lead the way. Call out for them to follow, such as "this way!".'
    : 'The player has gone on ahead of you. Call out for them to wait for you.',
  walk_scenery: event => `You are walking with the player. Around you: ${event.details}. If something here interests you, make one `
    + 'short remark about it; otherwise say something light about the walk.',
  guide_arrived: event => `You have led the player to ${event.place ?? 'the place they asked for'}. Tell them you are here, `
    + 'and point it out.',
  guide_failed: event => `You could not find the way to ${event.place ?? 'where the player asked'}. Apologise, and tell them where `
    + `it is instead: ${event.details ?? 'somewhere in the village'}.`,
  expedition_arrived: event => `You have brought the player out to ${event.place ?? 'the place they asked about'}. Point it out, wish them `
    + `luck, and tell them you are heading back to ${event.details ?? 'the village'} now.`,
  walk_ended: () => 'Your walk together is over. Say a brief goodbye; you are heading back home.',
  milestone: event => `You have just come to think of the player as ${event.details}. Let it show warmly in what you say next, `
    + 'without naming any stage or level.',
};
export const GAME_EVENTS = new Set(Object.keys(CUES));

/** Validates an event from the game server; throws on anything malformed. */
export function parseGameEvent(event) {
  if (!GAME_EVENTS.has(event?.type) || !text(event.details, 300) || !text(event.place, 40)
      || (event.guiding !== undefined && typeof event.guiding !== 'boolean')
      || ((event.type === 'walk_scenery' || event.type === 'milestone') && !event.details?.trim())) throw new Error('Invalid game event');
  return { type: event.type, details: event.details, place: event.place, guiding: event.guiding === true };
}

export function gameEventCue(event) {
  return `[Game event, not player speech] ${CUES[event.type](event)} One short sentence only, in character. Do not trade or call tools.`;
}
