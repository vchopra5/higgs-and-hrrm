// Rescore frozen generations with corrected judge evidence, preserving original judgments.
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { judgeTrial, summarize } from './run.mjs';
const [source, output] = process.argv.slice(2);
if (!source || !output || resolve(source) === resolve(output)) throw new Error('Usage: node rejudge.mjs input.json NEW-output.json');
try { await readFile(output); throw new Error('Output already exists'); } catch (e) { if (e.code !== 'ENOENT') throw e; }
process.loadEnvFile(fileURLToPath(new URL('../../../.env', import.meta.url)));
if (!process.env.OPENROUTER_API_KEY) throw new Error('OPENROUTER_API_KEY missing');
const raw = await readFile(source, 'utf8'), report = JSON.parse(raw);
const judgeSource = await readFile(new URL('./run.mjs', import.meta.url), 'utf8');
report.rejudging = { source, originalReportSha256: createHash('sha256').update(raw).digest('hex'),
  judgeSourceSha256: createHash('sha256').update(judgeSource).digest('hex'), createdAt: new Date().toISOString(),
  reason: 'Supply explicit chronological speech/tool/result events to prevent property order being mistaken for chronology.', humanReviewed: false };
// Bounded concurrency; inference outputs are never changed or regenerated.
for (let start = 0; start < report.results.length; start += 3) {
  await Promise.all(report.results.slice(start, start + 3).map(async r => {
    if (r.status !== 'completed') return;
    r.previousJudge = r.judge ?? null; delete r.judge;
    const f = report.fixtures.find(f => f.id === r.fixtureId);
    try { r.judge = await judgeTrial(f, r, { apiKey: process.env.OPENROUTER_API_KEY, model: report.options.judgeModel }); delete r.judgeError; }
    catch { r.judgeError = 'Judge request or verdict validation failed'; }
  }));
  report.summary = summarize(report.results);
  await writeFile(output, JSON.stringify(report, null, 2) + '\n');
  console.log(`Rejudged ${Math.min(start + 3, report.results.length)}/${report.results.length}`);
}
