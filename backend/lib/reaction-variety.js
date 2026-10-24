// Word-frequency cosine similarity. Filler and punctuation do not disguise repeats.
// This measures wording overlap, not semantic similarity from an embedding model.
function frequencies(text) {
  const result = new Map();
  for (const word of String(text).toLowerCase().replace(/[^a-z0-9\s]/g, '').split(/\s+/)) {
    if (!word || ['ow', 'ouch', 'hey', 'oh', 'ah', 'please'].includes(word)) continue;
    result.set(word, (result.get(word) ?? 0) + 1);
  }
  return result;
}
export function reactionSimilarity(a, b) {
  const x = frequencies(a), y = frequencies(b);
  const normX = [...x.values()].reduce((sum, n) => sum + n * n, 0);
  const normY = [...y.values()].reduce((sum, n) => sum + n * n, 0);
  if (!normX || !normY) return !normX && !normY ? 1 : 0;
  let dot = 0;
  for (const [word, n] of x) dot += n * (y.get(word) ?? 0);
  return dot / Math.sqrt(normX * normY);
}
export function repeatedReaction(text, history) {
  return history.some(line => reactionSimilarity(text, line) > 0.7);
}
