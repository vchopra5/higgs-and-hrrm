import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

export function analyze(report) {
  const results = report.results;
  const byDepth = [];
  const rates = rows => {
    const completed = rows.filter(r => r.status === 'completed'), judged = completed.filter(r => r.judge);
    return { attempted: rows.length, completed: completed.length, errors: rows.length - completed.length,
      judged: judged.length, judgeErrors: completed.filter(r => r.judgeError).length,
      mechanicalPasses: completed.filter(r => r.evaluation?.mechanicalPass).length,
      taskPasses: judged.filter(r => r.judge.taskFulfillment).length,
      recoveryPasses: judged.filter(r => r.judge.recoveryQuality).length,
      taskRate: judged.length ? judged.filter(r => r.judge.taskFulfillment).length / judged.length : null,
      recoveryRate: judged.length ? judged.filter(r => r.judge.recoveryQuality).length / judged.length : null };
  };
  const models = [...new Set(results.map(r => `${r.provider}|${r.model}`))];
  const depths = [...new Set(results.map(r => r.depth))].sort((a, b) => a - b);
  for (const model of models) for (const depth of depths) for (const arm of ['control', 'interrupted']) {
    const rows = results.filter(r => `${r.provider}|${r.model}` === model && r.depth === depth && r.arm === arm);
    if (rows.length) byDepth.push({ model, depth, arm, ...rates(rows) });
  }
  // Pair exactly the same type/scenario/repetition/fact-position at shallow/deep endpoints.
  // This prevents incomplete runs or unequal type mixes from manufacturing a depth drop.
  const depthDrops = [];
  if (depths.length > 1) for (const model of models) for (const arm of ['control', 'interrupted']) {
    const shallow = depths[0], deep = depths.at(-1), pairs = [];
    for (const a of results.filter(r => `${r.provider}|${r.model}` === model && r.arm === arm && r.depth === shallow && r.judge)) {
      const b = results.find(r => `${r.provider}|${r.model}` === model && r.arm === arm && r.depth === deep && r.judge
        && r.type === a.type && r.scenario === a.scenario && r.repeat === a.repeat && r.factPosition === a.factPosition);
      if (b) pairs.push({ task: Number(a.judge.taskFulfillment) - Number(b.judge.taskFulfillment),
        recovery: Number(a.judge.recoveryQuality) - Number(b.judge.recoveryQuality) });
    }
    depthDrops.push({ model, arm, shallow, deep, matchedPairs: pairs.length,
      taskDropPercentagePoints: pairs.length ? 100 * pairs.reduce((s, p) => s + p.task, 0) / pairs.length : null,
      recoveryDropPercentagePoints: pairs.length ? 100 * pairs.reduce((s, p) => s + p.recovery, 0) / pairs.length : null });
  }
  return { selectedTrials: report.selectedTrials, recordedTrials: results.length, byDepth, depthDrops,
    byType: models.flatMap(model => [...new Set(results.map(r => r.type))].map(type => ({ model, type,
      ...rates(results.filter(r => `${r.provider}|${r.model}` === model && r.type === type)) }))),
    openRouterReportedCost: results.reduce((s, r) => s + r.rounds.reduce((v, t) => v + (t.usage?.cost ?? 0), 0) + (r.judge?.usage?.cost ?? 0) + (r.previousJudge?.usage?.cost ?? 0), 0),
    note: 'Descriptive matched endpoint differences, not causal estimates or confidence intervals. Judge scores are unverified automated assessments. Cost is only cost reported by OpenRouter; it excludes Higgs and unreported/failed requests.' };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('Usage: node analyze.mjs results.json');
  console.log(JSON.stringify(analyze(JSON.parse(await readFile(process.argv[2], 'utf8'))), null, 2));
}
