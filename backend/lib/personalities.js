import { readFileSync } from 'node:fs';

const defaults = { warm: 'chloe', thoughtful: 'oliver', steady: 'marcus' };
const presets = new Set(['default', 'chloe', 'eleanor', 'jake', 'marcus', 'nora', 'oliver']);
const text = (value, max) => typeof value === 'string' && value.trim().length > 0
  && value.length <= max && !/[\u0000-\u001f\u007f-\u009f]/u.test(value);
const id = value => typeof value === 'string' && /^[a-z][a-z0-9_]{0,31}$/.test(value);
export function parseCatalogue(document) {
  if (document?.schemaVersion !== 1 || !Array.isArray(document.versions) || !document.versions.length) {
    throw new Error('Invalid personality catalogue');
  }
  const versions = new Map();
  for (const edition of document.versions) {
    if (!edition || !Number.isInteger(edition.version) || edition.version < 1 || versions.has(edition.version)
        || !Array.isArray(edition.archetypes) || !edition.archetypes.length || !Array.isArray(edition.styles)
        || !edition.styles.length || !Array.isArray(edition.names) || !edition.names.length) throw new Error('Invalid personality edition');
    const styles = new Set();
    const archetypes = new Set();
    for (const style of edition.styles) {
      if (!style || !id(style.id) || styles.has(style.id) || !text(style.delivery, 400)) throw new Error('Invalid personality style');
      styles.add(style.id);
    }
    for (const a of edition.archetypes) {
      if (!a || !id(a.id) || archetypes.has(a.id) || !text(a.name, 40) || !styles.has(a.defaultStyle)
          || !Object.hasOwn(defaults, a.voiceKey) || !text(a.motive, 240) || !text(a.contradiction, 240)
          || !text(a.socialStyle, 600) || !Array.isArray(a.interests) || !a.interests.length || a.interests.length > 8
          || !a.interests.every(term => text(term, 32)) || !Number.isInteger(a.gossipDelayMs) || a.gossipDelayMs < 0
          || a.gossipDelayMs > 300000 || !Number.isInteger(a.gossipThreshold) || a.gossipThreshold < 55 || a.gossipThreshold > 100
          || !Number.isInteger(a.cooldownPercent) || a.cooldownPercent < 100 || a.cooldownPercent > 400) throw new Error('Invalid personality archetype');
      archetypes.add(a.id);
    }
    if (!edition.names.every(name => text(name, 40)) || new Set(edition.names).size !== edition.names.length) throw new Error('Invalid personality names');
    versions.set(edition.version, edition);
  }
  if (!versions.has(document.currentVersion)) throw new Error('Invalid current personality edition');
  return versions;
}
const editions = parseCatalogue(JSON.parse(readFileSync(new URL('../../shared/personality-catalogue.json', import.meta.url), 'utf8')));

export function validateVoiceOverrides(overrides = {}) {
  for (const [key, value] of Object.entries(overrides)) {
    if (!Object.hasOwn(defaults, key) || (value !== undefined && !presets.has(value) && !/^voice_[a-zA-Z0-9_-]{1,128}$/.test(value))) {
      throw new Error('Invalid personality voice mapping');
    }
  }
  return Object.fromEntries(Object.entries(overrides).filter(([, value]) => value !== undefined));
}

export function resolveCharacter(context, overrides = {}) {
  const profile = context.personality;
  if (profile === undefined) return null;
  if (!profile || Array.isArray(profile) || Object.keys(profile).length !== 4
      || !Number.isInteger(profile.familiarity) || profile.familiarity < 0 || profile.familiarity > 20) {
    throw new Error('Invalid personality profile');
  }
  const edition = editions.get(profile.catalogueVersion);
  const archetype = edition?.archetypes.find(item => item.id === profile.archetypeId);
  const style = edition?.styles.find(item => item.id === profile.styleId);
  if (!archetype || !style) throw new Error('Unknown personality profile');
  const familiarity = profile.familiarity === 0 ? 'This is your first confirmed conversation with this player.'
    : profile.familiarity < 3 ? 'You recognize this player from previous conversations.'
      : 'This player is a familiar regular; allow a little more ease and warmth.';
  return {
    voice: { ...defaults, ...validateVoiceOverrides(overrides) }[archetype.voiceKey],
    instructions: `Your enduring character: ${archetype.motive} ${archetype.contradiction} ${archetype.socialStyle} `
      + `Vocal delivery: ${style.delivery} Personal interests: ${archetype.interests.join(', ')}. ${familiarity} `
      + 'Express these traits naturally without reciting a profile or repeating catchphrases. Your profession is your job, not your identity. '
      + 'Familiarity is recognition, never proof of trust or an invented past event. Personality never overrides tool facts, privacy, prices, stock or purchase consent. ',
  };
}

export function isVoiceSelectionError(error) {
  return error?.type === 'invalid_request_error'
    && ['audio.output.voice', 'session.audio.output.voice'].includes(error.param);
}
