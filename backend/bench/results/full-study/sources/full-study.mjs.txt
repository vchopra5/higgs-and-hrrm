import { readFile, writeFile, mkdir, appendFile, rename, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildFixture, TYPES, DEPTHS, SCENARIOS } from './fixtures.mjs';
import { runTrial, judgeTrial, MODELS, summarize } from './run.mjs';
import { scoreTrial, JUDGE_VERSION } from './judge.mjs';
import { pairedSchedule } from './schedule.mjs';
import { synthesize, pcmFromWave } from './audio.mjs';
const HERE = dirname(fileURLToPath(import.meta.url));
const sha = value => createHash('sha256').update(value).digest('hex');
const arg = (name, fallback) => process.argv.find(v => v.startsWith(`--${name}=`))?.slice(name.length + 3) ?? fallback;
const out = resolve(arg('out', resolve(HERE, '../results/full-study'))), resume = process.argv.includes('--resume');
const workers = Number(arg('workers', '8'));
if (!Number.isInteger(workers) || workers < 1 || workers > 12) throw new Error('workers must be 1–12');
const seed = 260628;
let schedule = [];
for (let repeat = 1; repeat <= 2; repeat++) for (const type of TYPES) for (const scenario of SCENARIOS)
  for (const depth of DEPTHS) for (const arm of ['control', 'interrupted']) for (const provider of ['higgs', 'openrouter']) {
    const fixture = buildFixture({ type, scenario: scenario.id, depth, arm });
    schedule.push({ fixture, repeat, provider, model: MODELS[provider], trialId: `${provider}/${MODELS[provider]}/${fixture.id}/${repeat}` });
  }
schedule = pairedSchedule(schedule, seed);
if (process.argv.includes('--dry')) { console.log(JSON.stringify({ trials: schedule.length, workers, seed, models: MODELS, depths: DEPTHS, types: TYPES })); process.exit(0); }
process.loadEnvFile(resolve(HERE, '../../../.env'));
const keys = { higgs: process.env.BOSON_API_KEY, openrouter: process.env.OPENROUTER_API_KEY };
if (!keys.higgs || !keys.openrouter) throw new Error('Both configured API keys are required');
const calibration = JSON.parse(await readFile(resolve(HERE, '../results/recovery-judge-calibration-v2.json'), 'utf8'));
if (calibration.version !== JUDGE_VERSION || calibration.results.length !== 24 || calibration.results.some(c => !c.agreement))
  throw new Error('Calibration gate failed: require 24/24 exact two-axis matches');
const files = (await readdir(HERE)).filter(f => f.endsWith('.mjs')).concat(['../../lib/protocol.js', '../../lib/personalities.js', '../../lib/vocal-delivery.js', '../../../shared/personality-catalogue.json', 'FULL-STUDY-PROTOCOL.md', 'reporting/analyze.py']);
const sources = {}, sourceHashes = {};
for (const p of files) { sources[p] = await readFile(resolve(HERE, p), 'utf8'); sourceHashes[p] = sha(sources[p]); }
const signature = sha(JSON.stringify({ sourceHashes, seed, models: MODELS, judge: 'anthropic/claude-sonnet-4.6', calibration: sha(JSON.stringify(calibration)) }));
const reportPath = resolve(out, 'results.json'), journal = resolve(out, 'results.jsonl');
let report;
if (resume) {
  report = JSON.parse(await readFile(reportPath, 'utf8'));
  if (report.signature !== signature) throw new Error('Cannot resume: frozen source/configuration changed');
  const records = (await readFile(journal, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l));
  report.results = [...new Map(records.map(r => [r.trialId, r])).values()];
} else {
  await mkdir(out, { recursive: false });
  report = { schemaVersion: 2, createdAt: new Date().toISOString(), track: 'IHBench-inspired Minecraft recorded-prefix recovery',
    signature, sourceHashes, seed, workers, nodeVersion: process.version,
    models: MODELS, judgeModel: 'anthropic/claude-sonnet-4.6', judgeVersion: JUDGE_VERSION,
    selectedTrials: 576, fixtures: [...new Map(schedule.map(t => [t.fixture.id, t.fixture])).values()],
    calibration: { path: '../recovery-judge-calibration-v2.json', sha256: sha(JSON.stringify(calibration)), matches: '24/24 assistant-labeled synthetic examples; no human validation' },
    retryPolicy: 'Up to two fresh retries for transport/API errors only, never for completed behavior, tool limits, or empty audio. All attempts retained. One retry for judge failure.',
    schedule: schedule.map(t => t.trialId), results: [] };
  await mkdir(resolve(out, 'sources')); await mkdir(resolve(out, 'audio'));
  for (const [p, source] of Object.entries(sources)) await writeFile(resolve(out, 'sources', p.replaceAll('../', '').replaceAll('/', '__') + '.txt'), source);
  await writeFile(journal, '');
  await writeFile(reportPath, JSON.stringify(report, null, 2));
}
const audioCache = new Map();
for (const text of new Set(schedule.map(t => t.fixture.question))) {
  const path = resolve(out, 'audio', `input-${sha(text)}.wav`);
  let audio;
  try { const wav = await readFile(path); const pcm = pcmFromWave(wav); audio = { wav, pcm, sha256: sha(wav), durationMs: pcm.length / 48 }; }
  catch (e) { if (e.code !== 'ENOENT') throw e; audio = await synthesize(text); await writeFile(path, audio.wav); }
  audioCache.set(text, audio);
}
let saveQueue = Promise.resolve();
const persist = r => {
  saveQueue = saveQueue.then(async () => {
    report.results.push(r);
    await appendFile(journal, JSON.stringify(r) + '\n');
    report.summary = summarize(report.results);
    report.updatedAt = new Date().toISOString();
    await writeFile(reportPath + '.tmp', JSON.stringify(report, null, 2)); await rename(reportPath + '.tmp', reportPath);
    console.log(JSON.stringify({ done: report.results.length, total: 576, id: r.trialId, status: r.status, attempts: r.attempts.length,
      task: r.scores?.taskSuccess, recovery: r.scores?.recoverySuccess, error: r.error, judgeError: r.judgeError }));
  });
  return saveQueue;
};
const done = new Set(report.results.map(r => r.trialId)), pending = schedule.filter(t => !done.has(t.trialId));
let index = 0, fatal = false;
await Promise.all(Array.from({ length: workers }, async () => {
  while (!fatal && index < pending.length) {
    const t = pending[index++], attempts = [];
    let r;
    for (let attempt = 1; attempt <= 3; attempt++) {
      r = await runTrial(t.fixture, { provider: t.provider, model: t.model, apiKey: keys[t.provider], audio: audioCache.get(t.fixture.question),
        upstreamUrl: process.env.HIGGS_REALTIME_URL, audioDir: resolve(out, 'audio') });
      attempts.push({ ...r, attempt, finishedAt: new Date().toISOString() });
      if (r.status !== 'error') break;
      if (/HTTP (401|402|403|400)/.test(r.error ?? '')) { fatal = true; break; }
      if (attempt < 3) await new Promise(r => setTimeout(r, 2000 * attempt));
    }
    r.trialId = t.trialId; r.repeat = t.repeat; r.attempts = attempts;
    if (r.status === 'completed') {
      r.judgeAttempts = [];
      for (let j = 0; j < 2; j++) {
        try { r.judge = await judgeTrial(t.fixture, r, { apiKey: keys.openrouter, model: report.judgeModel }); r.judgeAttempts.push({ status: 'completed' }); break; }
        catch { r.judgeAttempts.push({ status: 'error' }); }
      }
      if (!r.judge) r.judgeError = 'Judge failed after two attempts';
    }
    r.scores = scoreTrial(r);
    await persist(r);
  }
}));
await saveQueue;
report.finishedAt = new Date().toISOString(); report.complete = report.results.length === 576;
await writeFile(reportPath + '.tmp', JSON.stringify(report, null, 2)); await rename(reportPath + '.tmp', reportPath);
console.log(JSON.stringify({ complete: report.complete, trials: report.results.length }));
if (!report.complete) process.exitCode = 1;
