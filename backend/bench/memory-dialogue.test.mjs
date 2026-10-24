import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { loadCases, evaluateTranscript, runDialogueCase, summarize } from './memory-dialogue.mjs';

test('dialogue scoring keeps lookup narration separate from answer accuracy', async () => {
  const document = await loadCases();
  const fixture = document.cases.find(item => item.id === 'personal-building');
  const correct = evaluateTranscript(fixture, { transcript: 'You told me you were building a lighthouse beside the river.' });
  assert.equal(correct.lexicalRubricPass, true);
  assert.equal(correct.processNarration, false);
  const narrated = evaluateTranscript(fixture, { transcript: "I'll look up my memories. You were building a lighthouse." });
  assert.equal(narrated.lexicalRubricPass, true);
  assert.equal(narrated.processNarration, true);
  const wrong = evaluateTranscript(fixture, { transcript: 'You were building a castle.' });
  assert.equal(wrong.lexicalRubricPass, false);
});

test('provider benchmark preserves speech before a tool call and deduplicates its result', async () => {
  const document = await loadCases();
  const fixture = document.cases[0];
  const sent = [];
  let turn = 0;
  class Socket extends EventEmitter {
    readyState = 1;
    send(raw) {
      const event = JSON.parse(raw);
      sent.push(event);
      queueMicrotask(() => {
        if (event.type === 'session.update') {
          this.message({ type: 'session.created', session: { instructions: 'unconfigured session' } });
          this.message({ type: 'session.created', session: event.session });
        }
        if (event.type !== 'response.create') return;
        const id = `response-${++turn}`;
        this.message({ type: 'response.created', response: { id } });
        this.message({ type: 'response.output_audio_transcript.done', response_id: id, item_id: id,
          transcript: turn === 1 ? "I'll look up my memories." : 'You were building a lighthouse.' });
        const call = { type: 'function_call', name: 'recall_memories', call_id: 'lookup-1', arguments: '{"query":"building","scope":"player"}' };
        if (turn === 1) this.message({ ...call, type: 'response.function_call_arguments.done', response_id: id });
        this.message({ type: 'response.done', response: { id, status: 'completed', output: turn === 1 ? [call] : [] } });
      });
    }
    message(event) { this.emit('message', Buffer.from(JSON.stringify(event))); }
    close() { this.readyState = 3; }
    terminate() { this.readyState = 3; }
  }
  const result = await runDialogueCase({ fixture, asOf: document.asOf, arm: 'baseline', apiKey: 'synthetic-test-key',
    connect: () => { const socket = new Socket(); queueMicrotask(() => socket.emit('open')); return socket; } });
  assert.equal(result.status, 'completed');
  assert.match(result.transcript, /look up my memories/);
  assert.match(result.transcript, /lighthouse/);
  assert.equal(result.evaluation.processNarration, true);
  assert.equal(result.evaluation.lexicalRubricPass, true);
  assert.equal(result.toolCalls.length, 1);
  assert.equal(sent.filter(event => event.item?.type === 'function_call_output').length, 1);
  assert.equal(sent.filter(event => event.type === 'response.create').length, 2);
  assert.equal(summarize([result]).baseline.ordinary.completed, 1);
});

test('failed provider requests are counted separately from completed benchmark scores', () => {
  const summary = summarize([{ arm: 'prepared', status: 'timeout', category: 'known_fact' }]);
  assert.equal(summary.prepared.all.attempted, 1);
  assert.equal(summary.prepared.all.completed, 0);
  assert.equal(summary.prepared.all.failed, 1);
  assert.equal(summary.prepared.all.meanElapsedMs, null);
});

test('prepared arm uses production context delivery once across tool continuations', async () => {
  const document = await loadCases();
  const fixture = document.cases[0];
  const sent = [];
  let turn = 0;
  class Socket extends EventEmitter {
    readyState = 1;
    send(raw) {
      const event = JSON.parse(raw);
      sent.push(event);
      queueMicrotask(() => {
        if (event.type === 'session.update') this.emit('message', Buffer.from(JSON.stringify({ type: 'session.created', session: event.session })));
        if (event.type !== 'response.create') return;
        const id = `response-${++turn}`;
        const call = { type: 'function_call', name: 'remember_conversation', call_id: 'synthetic-save', arguments: '{"summary":"A synthetic event","private":false}' };
        this.emit('message', Buffer.from(JSON.stringify({ type: 'response.output_audio_transcript.done', response_id: id, item_id: id,
          transcript: turn === 1 ? '' : 'You were building a lighthouse.' })));
        this.emit('message', Buffer.from(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: turn === 1 ? [call] : [] } })));
      });
    }
    close() { this.readyState = 3; }
    terminate() { this.readyState = 3; }
  }
  const result = await runDialogueCase({ fixture, asOf: document.asOf, arm: 'prepared', apiKey: 'synthetic-test-key',
    connect: () => { const socket = new Socket(); queueMicrotask(() => socket.emit('open')); return socket; } });
  assert.equal(result.status, 'completed');
  const recollections = sent.filter(event => event.item?.content?.[0]?.text?.startsWith('[Recollection context; not player speech]'));
  assert.equal(recollections.length, 1);
  assert.equal(recollections[0].item.role, 'user');
  assert.match(recollections[0].item.content[0].text, /lighthouse/);
  const creates = sent.filter(event => event.type === 'response.create');
  assert.equal(creates.length, 2);
  assert.ok(creates.every(event => !event.response));
});
