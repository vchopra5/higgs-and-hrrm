// Keyword fallback for compliments and insults. Voice models often reply "thank you"
// without calling report_treatment; the server still assesses each turn only once.
const POLITE = new Set(['beautiful', 'lovely', 'gorgeous', 'pretty', 'handsome', 'cute', 'wonderful', 'amazing',
  'awesome', 'fantastic', 'brilliant', 'great', 'excellent', 'nice', 'kind', 'sweet', 'charming', 'clever', 'smart',
  'wise', 'best', 'love', 'adore', 'appreciate', 'thanks', 'thank', 'please', 'cheers', 'incredible', 'stunning',
  'favourite', 'favorite', 'talented', 'generous', 'friendly', 'helpful', 'impressive', 'cool', 'perfect']);
const RUDE = new Set(['stupid', 'idiot', 'ugly', 'dumb', 'moron', 'hate', 'useless', 'loser', 'pathetic', 'disgusting',
  'scam', 'scammer', 'thief', 'liar', 'worthless', 'trash', 'garbage', 'fool', 'jerk', 'smelly', 'stinky', 'annoying',
  'greedy', 'cheat', 'cheater', 'awful', 'terrible', 'horrible', 'rubbish', 'idiotic', 'clown', 'dummy', 'imbecile',
  'fraud', 'crook', 'coward', 'creep', 'freak', 'weirdo', 'lame', 'sucks', 'nitwit', 'buffoon', 'muppet', 'dimwit']);
const RUDE_PHRASES = ['shut up', 'get lost', 'go away', 'rip off', 'ripoff', 'you suck', 'screw you', 'piss off', 'drop dead'];
const NEGATIONS = new Set(['not', "don't", 'dont', 'never', 'no', "isn't", 'isnt', "aren't", 'arent', "wasn't", "doesn't"]);

// This is an English fallback, not a language detector. Do not strip other scripts
// into accidental English words ("kind" also means child in German). Ambiguous
// wording is left to the audio model's report_treatment tool in any language.
const ENGLISH_CONTEXT = /^(?:you(?:\b|'re)|your\b|that(?:\b|'s)|this\b|i\b|we\b|could i\b|can i\b|would you\b|thank you\b|thanks\b|please\b|cheers\b|nice (?:hat|shop|work|eyes)\b|shut up\b|get lost\b|go away\b|screw you\b|piss off\b|drop dead\b)/i;

/** Returns 'polite', 'rude' or 'neutral' for one player utterance. Insults win over compliments. */
export function classifyTreatment(text) {
  const original = String(text ?? '').trim().replace(/[’]/g, "'");
  if (/[^\x00-\x7f]/u.test(original) || !ENGLISH_CONTEXT.test(original)) return 'neutral';
  const clean = original.toLowerCase().replace(/[^a-z'\s]/g, ' ');
  const words = clean.split(/\s+/).filter(Boolean);
  const joined = words.join(' ');
  let polite = false;
  let rude = RUDE_PHRASES.some(phrase => joined.includes(phrase));
  words.forEach((word, index) => {
    // "not stupid" and "not beautiful" are ambiguous; ignore negated words either way.
    if (NEGATIONS.has(words[index - 1]) || NEGATIONS.has(words[index - 2])) return;
    if (RUDE.has(word)) rude = true;
    else if (POLITE.has(word)) polite = true;
  });
  return rude ? 'rude' : polite ? 'polite' : 'neutral';
}
