import test from 'node:test';
import assert from 'node:assert/strict';
import { MemoryContext } from '../lib/memory-context.js';

function fixture(t) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const session = {
    id: 'session', pending: new Map(), context: { memories: [{ summary: 'Initial memory' }] },
    now: () => 1234, flushed: 0,
    flushPreparedResponse() { this.flushed++; },
  };
  const memory = new MemoryContext(session, { transcriptionMs: 100, recallMs: 50 });
  t.after(() => memory.close());
  return { memory, session, tick: ms => t.mock.timers.tick(ms) };
}

function transcribe(memory, turnId = 1, itemId = 'u1', text = 'Where was the violet lantern?') {
  memory.beginTurn(turnId);
  memory.committed(itemId);
  memory.transcript(itemId, text);
}

test('prepares retrieved evidence before releasing the spoken response', t => {
  const { memory, session } = fixture(t);
  transcribe(memory);
  const tool = [...session.pending.values()][0];
  assert.deepEqual(tool, {
    callId: 'memory-recall-session-1', name: '_recall_turn',
    arguments: { query: 'Where was the violet lantern?' }, internal: true,
    memoryContext: true, turnId: 1, created: 1234,
  });
  assert.equal(memory.waiting, true);
  assert.equal(session.flushed, 0);
  const memories = [{ summary: 'A violet lantern is beneath the well', source: 'player_told_me' }];
  assert.equal(memory.acceptResult(tool, { memories }), true);
  assert.deepEqual(session.preparedMemory, { memories, status: 'ready', asOf: 1234 });
  assert.equal(memory.waiting, false);
  assert.equal(session.pending.size, 0);
  assert.equal(session.flushed, 1);
  assert.equal(memory.acceptResult(tool, { memories: [] }), false);
  assert.equal(session.flushed, 1);
});

test('matches transcription arriving before commit and ignores duplicates', t => {
  const { memory, session, tick } = fixture(t);
  memory.beginTurn(1);
  memory.transcript('u1', 'First transcript');
  memory.transcript('u1', 'Duplicate transcript');
  assert.equal(session.pending.size, 0);
  memory.committed('u1');
  assert.equal([...session.pending.values()][0].arguments.query, 'First transcript');
  tick(25);
  memory.committed('u1');
  memory.transcript('u1', 'Another duplicate');
  tick(25);
  assert.equal(session.preparedMemory.status, 'unavailable');
  assert.equal(session.flushed, 1, 'duplicates must not extend the retrieval deadline');
});

test('late commits and transcripts from cancelled turns cannot be assigned to the next turn', t => {
  const { memory, session } = fixture(t);
  memory.beginTurn(1);
  memory.cancel();
  memory.beginTurn(2);
  memory.transcript('old', 'Wrong story');
  memory.committed('old');
  assert.equal(session.pending.size, 0);
  memory.committed('current');
  memory.transcript('old', 'Still wrong');
  memory.transcript('current', 'Current question');
  const tool = [...session.pending.values()][0];
  assert.equal(tool.turnId, 2);
  assert.equal(tool.arguments.query, 'Current question');
  assert.equal(session.flushed, 0);
});

test('cancelled retrieval results cannot overwrite a subsequent turn', t => {
  const { memory, session } = fixture(t);
  transcribe(memory);
  const stale = [...session.pending.values()][0];
  memory.cancel();
  assert.equal(session.pending.size, 0);
  transcribe(memory, 2, 'u2', 'Second question');
  const current = [...session.pending.values()][0];
  assert.equal(memory.acceptResult(stale, { memories: [{ summary: 'Stale' }] }), false);
  assert.equal(memory.waiting, true);
  memory.acceptResult(current, { memories: [] });
  assert.deepEqual(session.preparedMemory.memories, [], 'an empty successful recall is evidence of no match');
  assert.equal(session.flushed, 1);
});

test('transcription timeout retains initial context and ignores later completion', t => {
  const { memory, session, tick } = fixture(t);
  memory.beginTurn(1);
  memory.committed('late');
  tick(100);
  assert.deepEqual(session.preparedMemory, { memories: session.context.memories, status: 'unavailable', asOf: 1234 });
  memory.committed('late');
  memory.transcript('late', 'Too late');
  assert.equal(session.pending.size, 0);
  assert.equal(session.flushed, 1);
});

test('an unacknowledged commit fails safely instead of shifting subsequent turn identities', t => {
  const { memory, session, tick } = fixture(t);
  session.failMemoryCommit = () => { session.closed = true; memory.close(); };
  memory.beginTurn(1);
  tick(100);
  assert.equal(session.closed, true);
  assert.equal(session.flushed, 0);
  assert.equal(memory.beginTurn(2), false);
  assert.equal(session.preparedMemory, null);
});

test('recall timeout removes its pending tool and keeps initial context', t => {
  const { memory, session, tick } = fixture(t);
  transcribe(memory);
  const tool = [...session.pending.values()][0];
  tick(50);
  assert.equal(session.pending.size, 0);
  assert.equal(memory.acceptResult(tool, { memories: [{ summary: 'Late evidence' }] }), false);
  assert.equal(session.preparedMemory.status, 'unavailable');
  assert.deepEqual(session.preparedMemory.memories, session.context.memories);
  assert.equal(session.flushed, 1);
});

test('transcription failure works before or after a commit acknowledgement', t => {
  const { memory, session } = fixture(t);
  memory.beginTurn(1);
  memory.failed('u1');
  memory.committed('u1');
  assert.equal(session.preparedMemory.status, 'unavailable');
  memory.beginTurn(2);
  memory.committed('u2');
  memory.failed('u1');
  assert.equal(memory.waiting, true);
  memory.failed('u2');
  assert.equal(session.flushed, 2);
  assert.equal(session.pending.size, 0);
});

test('empty transcripts and failed or malformed tool results degrade without invented memories', t => {
  const { memory, session } = fixture(t);
  transcribe(memory, 1, 'u1', '   ');
  assert.equal(session.pending.size, 0);
  transcribe(memory, 2, 'u2');
  memory.acceptResult([...session.pending.values()][0], { error: 'Storage unavailable', memories: [] });
  assert.equal(session.preparedMemory.status, 'unavailable');
  transcribe(memory, 3, 'u3');
  memory.acceptResult([...session.pending.values()][0], { memories: 'wrong type' });
  assert.equal(session.flushed, 3);
  assert.deepEqual(session.preparedMemory.memories, session.context.memories);
});

test('queries and unmatched-event buffers are bounded without shifting late commit identity', t => {
  const { memory, session } = fixture(t);
  for (let turn = 1; turn <= 100; turn++) memory.beginTurn(turn);
  assert.equal(memory.commits.length, 64);
  for (let turn = 1; turn <= 99; turn++) memory.committed(`u${turn}`);
  for (let item = 0; item < 100; item++) memory.transcript(`unknown-${item}`, 'Unused');
  assert.equal(memory.transcripts.size, 64);
  memory.committed('u100');
  memory.transcript('u100', 'a'.repeat(5000));
  const tool = [...session.pending.values()][0];
  assert.equal(tool.turnId, 100);
  assert.equal(tool.arguments.query.length, 4000);
  assert.equal(memory.items.size, 64);
});

test('close cancels timers and reads and never resumes a response', t => {
  const { memory, session, tick } = fixture(t);
  transcribe(memory);
  const tool = [...session.pending.values()][0];
  memory.close();
  tick(1000);
  memory.committed('u2');
  memory.transcript('u2', 'Closed');
  assert.equal(memory.beginTurn(2), false);
  assert.equal(memory.acceptResult(tool, { memories: [] }), false);
  assert.equal(memory.waiting, false);
  assert.equal(session.pending.size, 0);
  assert.equal(session.flushed, 0);
  assert.equal(memory.commits.length, 0);
});
