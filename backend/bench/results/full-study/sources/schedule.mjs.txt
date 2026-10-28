import { createHash } from 'node:crypto';
export function pairedSchedule(trials, seed) {
  const hash = s => createHash('sha256').update(`${seed}:${s}`).digest('hex');
  const blocks = new Map();
  for (const t of trials) {
    const key = `${t.fixture.id}/${t.repeat}`;
    const block = blocks.get(key) ?? []; block.push(t); blocks.set(key, block);
  }
  return [...blocks.entries()].sort(([a],[b]) => hash(a).localeCompare(hash(b))).flatMap(([key, block]) => {
    if (block.length !== 2 || new Set(block.map(t => t.provider)).size !== 2) throw new Error('Every block must contain both providers');
    return [...block].sort((a,b) => hash(`${key}/${a.provider}`).localeCompare(hash(`${key}/${b.provider}`)));
  });
}
