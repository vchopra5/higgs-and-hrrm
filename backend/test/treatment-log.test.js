import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { treatmentLogger } from '../lib/treatment-log.js';

test('treatment diagnostics append escaped speech and outcomes without persisting other logs', t => {
  const folder = mkdtempSync(join(tmpdir(), 'higgs-treatment-'));
  t.after(() => rmSync(folder, { recursive: true, force: true }));
  const path = join(folder, 'logs', 'treatment.jsonl');
  const output = [];
  const log = treatmentLogger(path, line => output.push(line));
  const classification = { event: 'treatment_classified', source: 'keyword_fallback',
    transcript: 'Nice eyes!\n"Hello"', treatment: 'polite', turnId: 1 };
  log(classification);
  log({ event: 'voice_stage', stage: 'upstream_ready' });
  const result = { event: 'treatment_result', source: 'voice_model', status: 'unchanged', turnId: 1 };
  treatmentLogger(path, () => {})(result);
  assert.deepEqual(readFileSync(path, 'utf8').trim().split('\n').map(JSON.parse), [classification, result]);
  assert.equal(output.length, 2);
  assert.equal(statSync(path).mode & 0o777, 0o600);
});

test('a log write failure reports an error without interrupting voice processing', t => {
  const folder = mkdtempSync(join(tmpdir(), 'higgs-treatment-'));
  t.after(() => rmSync(folder, { recursive: true, force: true }));
  const output = [];
  const log = treatmentLogger(folder, line => output.push(JSON.parse(line)));
  assert.doesNotThrow(() => log({ event: 'treatment_result', status: 'assessed' }));
  assert.equal(output.at(-1).event, 'treatment_log_error');
});
