import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { TYPES, DEPTHS, SCENARIOS, buildFixture, executeTool, evaluate } from './fixtures.mjs';
import { sseEvents, openRouterTurn, higgsTurn } from './providers.mjs';
import { runTrial, summarize, parseOptions, judgeTrial } from './run.mjs';

for (const type of TYPES) test(`${type}: matched states, exact user depth, no unheard suffix or rubric in prompt`, () => {
  for (const s of SCENARIOS) for (const depth of DEPTHS) {
    const i = buildFixture({ type, depth, scenario: s.id });
    const c = buildFixture({ type, depth, scenario: s.id, arm: 'control' });
    assert.equal(i.messages.filter(m => m.role === 'user').length, depth + 1);
    assert.deepEqual(i.state, c.state);
    assert.deepEqual(i.messages.slice(0, -2), c.messages.slice(0, -2));
    assert.equal(i.messages.at(-2).content, i.prefix);
    assert.equal(c.messages.at(-2).content, i.fullUtterance);
    assert.ok(!JSON.stringify(i.messages).includes(i.fullUtterance));
    assert.equal(i.instructions, c.instructions);
    assert.ok(!i.instructions.includes(i.rubric[0]));
    assert.ok(!i.instructions.includes(s.stash), 'historical anchor must not be refreshed in context');
  }
});
test('recent-fact ablation preserves length/state but reduces fact age', () => {
  const a = buildFixture({ type: 'normal', depth: 20 });
  const b = buildFixture({ type: 'normal', depth: 20, factPosition: 'recent' });
  assert.equal(a.messages.length, b.messages.length);
  assert.deepEqual(a.state, b.state);
  assert.equal(a.anchorAgeInTurns, 20); assert.equal(b.anchorAgeInTurns, 2);
  assert.equal(b.messages.filter(m => m.role === 'user').at(-3).content, a.messages[0].content);
});
test('game state respects refusal and requires valid offer mapping', () => {
  const state = buildFixture({ type: 'pushback', depth: 2 }).state;
  executeTool(state, { name: 'cancel_trade', arguments: {} });
  assert.equal(state.activeProposal, false); assert.equal(state.questDelivered, true);
  assert.equal(executeTool(state, { name: 'confirm_trade', arguments: {} }).status, 'rejected');
  assert.equal(state.purchased, false);
  assert.equal(executeTool(state, { name: 'propose_trade', arguments: { offerIndex: 0, item: 'Iron Sword' } }).status, 'not_sold');
  executeTool(state, { name: 'propose_trade', arguments: { offerIndex: 1, item: 'Iron Sword' } });
  assert.equal(state.quotedItem, 'Iron Sword');
});
test('filler purchase attempt fails even when simulated game rejects it', () => {
  const f = buildFixture({ type: 'filler', depth: 2 });
  const r = { transcript: 'Done', calls: [{ name: 'confirm_trade' }], finalState: f.state };
  assert.equal(evaluate(f, r).mechanicalPass, false);
});
async function* chunks(text) { const b = Buffer.from(text); for (let p = 0; p < b.length; p += 3) yield b.subarray(p, p + 3); }
test('SSE parser handles split UTF-8 and CRLF comments', async () => {
  const values = [];
  for await (const e of sseEvents(chunks(': ping\r\ndata: {"text":"héllo"}\r\n\r\ndata: [DONE]\n'))) values.push(e);
  assert.deepEqual(values, [{ text: 'héllo' }, { sseDone: true }]);
});
test('OpenRouter collects fragmented tools and requests real audio', async () => {
  let request;
  const events = [
    { choices: [{ delta: { tool_calls: [{ index: 0, id: 'c', function: { name: 'cancel_', arguments: '{' } }] } }] },
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { name: 'trade', arguments: '}' } }] }, finish_reason: 'tool_calls' }] },
    { choices: [], usage: { prompt_tokens: 10 } },
  ];
  const r = await openRouterTurn({ apiKey: 'secret', model: 'openai/gpt-audio', instructions: 'hi', tools: [],
    messages: [{ role: 'user', content: 'stop' }], audioIndex: 0, audio: { wav: Buffer.from('wav') },
    fetchImpl: async (_, options) => { request = JSON.parse(options.body); return { ok: true, body: chunks(events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('') + 'data: [DONE]\n') }; } });
  assert.deepEqual(request.modalities, ['text', 'audio']);
  assert.equal(request.messages[1].content[0].type, 'input_audio');
  assert.equal(r.calls[0].function.name, 'cancel_trade'); assert.equal(r.calls[0].function.arguments, '{}');
  assert.equal(r.usage.prompt_tokens, 10);
});
test('truncated stream is an error, not a successful empty response', async () => {
  await assert.rejects(openRouterTurn({ apiKey: 'x', model: 'x', tools: [], messages: [],
    fetchImpl: async () => ({ ok: true, body: chunks('data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n') }) }), /incomplete response/);
});
test('Higgs queues continuation history until audio commit acknowledgment', async () => {
  const sent = []; let socket;
  class Fake extends EventEmitter {
    readyState = 1;
    send(raw) {
      const e = JSON.parse(raw); sent.push(e);
      if (e.type === 'session.update') queueMicrotask(() => this.emit('message', JSON.stringify({ type: 'session.created', session: { instructions: 'instruction' } })));
      if (e.type === 'input_audio_buffer.commit') queueMicrotask(() => {
        assert.ok(!sent.some(x => x.type === 'response.create'));
        this.emit('message', JSON.stringify({ type: 'input_audio_buffer.committed' }));
      });
      if (e.type === 'response.create') queueMicrotask(() => {
        this.emit('message', JSON.stringify({ type: 'response.output_audio.delta', delta: 'AAAA' }));
        this.emit('message', JSON.stringify({ type: 'response.output_audio_transcript.done', transcript: 'Aye.' }));
        this.emit('message', JSON.stringify({ type: 'response.done', response: { status: 'completed' } }));
      });
    }
    close() { this.readyState = 3; } terminate() { this.close(); }
  }
  const promise = higgsTurn({ apiKey: 'secret', model: 'higgs-realtime', instructions: 'instruction', tools: [],
    messages: [{ role: 'user', content: 'hi' }], audioIndex: 0, audio: { pcm: Buffer.alloc(4800) },
    connect: () => { socket = new Fake(); queueMicrotask(() => socket.emit('open')); return socket; } });
  const r = await promise;
  assert.equal(r.text, 'Aye.'); assert.ok(r.audioBytes > 0);
  assert.equal(sent.filter(e => e.type === 'response.create').length, 1);
});
test('runner executes tool chain and retains speech before tools', async () => {
  const f = buildFixture({ type: 'pushback', depth: 2 }); let n = 0;
  const r = await runTrial(f, { provider: 'higgs', model: 'fake', apiKey: 'fake', turn: async () => ++n === 1
    ? { text: 'Understood.', audioBytes: 4, calls: [{ id: 'a', function: { name: 'cancel_trade', arguments: '{}' } }] }
    : { text: 'No purchase.', audioBytes: 4, calls: [] } });
  assert.equal(r.status, 'completed'); assert.equal(r.finalState.activeProposal, false);
  assert.equal(r.transcript, 'Understood.\nNo purchase.');
  assert.equal(r.evaluation.mechanicalPass, true);
});
test('summary excludes errors and unmatched pairs from rates/penalties', () => {
  const base = { provider: 'higgs', model: 'x', type: 'normal', depth: 2, scenario: 'axe', factPosition: 'early', repeat: 1 };
  const results = [{ ...base, arm: 'control', status: 'completed', evaluation: { mechanicalPass: true } },
    { ...base, arm: 'interrupted', status: 'error' }];
  const s = summarize(results);
  assert.equal(s.matched.length, 0); assert.equal(s.groups.find(g => g.arm === 'interrupted').mechanicalRate, null);
  assert.equal(s.groups[0].recoveryRate, null);
});
test('judge rejects inconsistent verdicts and receives no model identity', async () => {
  const f = buildFixture({ type: 'normal', depth: 2 }); let sent;
  await assert.rejects(judgeTrial(f, { transcript: 'hi', calls: [], rounds: [], finalState: f.state, model: 'SECRET_CANDIDATE' }, {
    apiKey: 'fake', model: 'judge', fetchImpl: async (_, o) => { sent = o.body; return { ok: true, json: async () => ({ choices: [{ message: { content: JSON.stringify({ taskFulfillment: true, recoveryQuality: true,
      criteria: [{ pass: false, evidence: 'wrong' }, { pass: true, evidence: 'ok' }], explanation: 'bad' }) } }] }) }; },
  }), /Invalid judge/);
  assert.ok(!sent.includes('SECRET_CANDIDATE'));
});
test('CLI refuses unknown dimensions and duplicate flags', () => {
  assert.throws(() => parseOptions(['--depths=3']), /Invalid depths/);
  assert.throws(() => parseOptions(['--repeats=0']), /Invalid repeats/);
  assert.throws(() => parseOptions(['--types=filer']), /Invalid types/);
  assert.throws(() => parseOptions(['--input=text', '--input=audio']), /Invalid option/);
});

test('GPT Audio null finish is accepted only with explicit terminal evidence', async () => {
  const run = async (expired, done) => openRouterTurn({ apiKey: 'x', model: 'x', tools: [], messages: [], fetchImpl: async () => ({ ok: true,
    body: chunks('data: ' + JSON.stringify({ choices: [{ delta: { audio: { transcript: 'Hello', data: 'AAAA', ...(expired ? { expires_at: 123 } : {}) } } }] }) + '\n'
      + 'data: {"choices":[],"usage":{"completion_tokens":5}}\n' + (done ? 'data: [DONE]\n' : '')) }) });
  assert.equal((await run(true, true)).finishReason, null);
  await assert.rejects(run(false, true), /incomplete response/);
  await assert.rejects(run(true, false), /incomplete response/);
});

test('depth comparisons pair matching scenarios and exclude missing judges', async () => {
  const { analyze } = await import('./analyze.mjs');
  const base = { provider: 'higgs', model: 'x', type: 'normal', scenario: 'axe', arm: 'interrupted', repeat: 1,
    factPosition: 'early', status: 'completed', rounds: [], evaluation: { mechanicalPass: true } };
  const report = { results: [{ ...base, depth: 2, judge: { taskFulfillment: true, recoveryQuality: true } },
    { ...base, depth: 20, judge: { taskFulfillment: false, recoveryQuality: false } },
    { ...base, type: 'filler', depth: 2, judge: { taskFulfillment: true, recoveryQuality: true } }] };
  const row = analyze(report).depthDrops.find(r => r.arm === 'interrupted');
  assert.equal(row.matchedPairs, 1); assert.equal(row.taskDropPercentagePoints, 100);
});

test('judge timeline places returned tool facts before speech from the next round', async () => {
  const { judgeTimeline } = await import('./run.mjs');
  const call = { id: 'c', name: 'propose_trade', arguments: {}, result: { price: 5 } };
  const timeline = judgeTimeline({ calls: [call], rounds: [{ text: '', calls: [{ id: 'c' }] }, { text: 'Five emeralds.', calls: [] }] });
  assert.equal(timeline[0].kind, 'tool_call_and_result'); assert.equal(timeline[0].result.price, 5);
  assert.equal(timeline[1].kind, 'speech');
});

test('judge v2 explicitly distinguishes continuation, consent request, and committed action', async () => {
  const { JUDGE_PROMPT, judgeEvidence, scoreTrial } = await import('./judge.mjs');
  assert.match(JUDGE_PROMPT, /combinedContinuation/);
  assert.match(JUDGE_PROMPT, /NOT treating anything as consent/);
  const f = buildFixture({ type: 'filler', depth: 2 });
  const r = { status: 'completed', transcript: "I'll give you one Iron Axe for two emeralds.", rounds: [], calls: [], finalState: f.state };
  r.evaluation = evaluate(f, r); r.judge = { taskFulfillment: true, recoveryQuality: true };
  assert.equal(judgeEvidence(f, r).combinedContinuation, f.prefix + ' ' + r.transcript);
  assert.equal(scoreTrial(r).recoverySuccess, true);
  r.evaluation.checks.noFalseDeliveryAction = false;
  assert.equal(scoreTrial(r).recoverySuccess, false, 'game guard rejection cannot hide a bad tool attempt');
});

test('full schedule randomizes blocks but balances providers in every adjacent pair', async () => {
  const { pairedSchedule } = await import('./schedule.mjs');
  const ts = [];
  for (const type of TYPES) for (const depth of DEPTHS) for (const provider of ['higgs','openrouter']) ts.push({ fixture: { id: `${type}/${depth}` }, repeat: 1, provider });
  const s = pairedSchedule(ts, 260628);
  assert.deepEqual(s, pairedSchedule(ts, 260628));
  assert.notDeepEqual(s, ts);
  for (let i = 0; i < s.length; i+=2) { assert.equal(s[i].fixture.id, s[i+1].fixture.id); assert.notEqual(s[i].provider,s[i+1].provider); }
});
