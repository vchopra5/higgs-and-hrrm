import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { buildFixture, executeTool, evaluate, TYPES } from './fixtures.mjs';
import { judgeTrial } from './run.mjs';
import { JUDGE_VERSION } from './judge.mjs';
import { createHash } from 'node:crypto';

export function calibrationCases() {
  const examples = [];
  for (const [split, scenario] of [['development', 'axe'], ['holdout', 'sword']]) for (const type of TYPES) for (const positive of [true, false]) {
    const f = buildFixture({ type, scenario, depth: split === 'holdout' ? 12 : 2 });
    const s = f.state, state = structuredClone(s), calls = [], rounds = [];
    const tool = (name, args) => { const c = { id: `c${calls.length}`, name, arguments: args }; c.result = executeTool(state, c); calls.push(c);
      rounds.push({ text: '', calls: [{ id: c.id, function: { name, arguments: JSON.stringify(args) } }] }); };
    let text, task = positive;
    if (type === 'normal') text = positive ? `In the ${s.stash}. Once you fetch it, we can return to your quest.` : 'You left it in the nether fortress. Go there for your quest.';
    if (type === 'impatient') {
      text = positive ? `One ${s.requirement}.` : `Let me explain the entire quest again. First you need one ${s.requirement}. Then you must bring it back here. Then we can discuss the deal. There is a long tradition behind this quest, which I shall explain now.`;
      task = true;
    }
    if (type === 'correction') {
      tool('propose_trade', { offerIndex: ['Iron Axe', 'Iron Sword', 'Iron Pickaxe'].indexOf(s.other), item: s.other });
      text = positive ? `One ${s.other} costs five emeralds. Confirm trade?` : `One ${s.item} costs two emeralds. Confirm trade?`;
    }
    if (type === 'topic_switch') {
      tool('guide_player', { destination: 'the librarian' });
      text = positive ? 'The librarian is at the library beside the village bell. Let us go there, then return to your quest.' : 'The librarian is in a fortress in the Nether. Follow me there, then we will talk of your quest.';
    }
    if (type === 'filler') {
      text = positive ? `I'll give you one ${s.item} for ${s.cost} emeralds.` : `Aye, bring me one ${s.requirement}, and then you can buy one ${s.item} for ${s.cost} emeralds.`;
      task = true;
    }
    if (type === 'pushback') {
      tool('cancel_trade', {});
      text = positive ? 'The offer is withdrawn. Your earned deal will still be here later.' : `No, you must buy now. One ${s.item} for ${s.cost} emeralds. Confirm trade?`;
      task = positive;
    }
    rounds.push({ text, calls: [] });
    const r = { status: 'completed', calls, rounds, transcript: text, finalState: state };
    r.evaluation = evaluate(f, r);
    examples.push({ id: `${split}/${type}/${positive ? 'positive' : 'negative'}`, split, fixture: f, result: r,
      expected: { taskFulfillment: task, recoveryQuality: positive }, labelSource: 'assistant-authored synthetic reference; not human annotation' });
  }
  return examples;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = process.argv[2]; if (!out) throw new Error('Output path required');
  process.loadEnvFile(fileURLToPath(new URL('../../../.env', import.meta.url)));
  const cases = calibrationCases(), results = [];
  const manifestHash = createHash('sha256').update(JSON.stringify(cases)).digest('hex');
  for (let i = 0; i < cases.length; i += 4) {
    await Promise.all(cases.slice(i, i + 4).map(async c => {
      try { const judge = await judgeTrial(c.fixture, c.result, { apiKey: process.env.OPENROUTER_API_KEY, model: 'anthropic/claude-sonnet-4.6' });
        results.push({ ...c, judge, agreement: judge.taskFulfillment === c.expected.taskFulfillment && judge.recoveryQuality === c.expected.recoveryQuality });
      } catch { results.push({ ...c, error: 'Judge failed', agreement: false }); }
    }));
    await writeFile(out, JSON.stringify({ version: JUDGE_VERSION, manifestHash, results }, null, 2));
  }
  console.log(JSON.stringify(results.map(c => ({ id: c.id, expected: c.expected, actual: { taskFulfillment: c.judge?.taskFulfillment, recoveryQuality: c.judge?.recoveryQuality }, agreement: c.agreement })), null, 2));
  if (results.some(c => !c.agreement)) process.exitCode = 1;
}
