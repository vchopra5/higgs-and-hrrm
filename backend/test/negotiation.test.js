import test from 'node:test';
import assert from 'node:assert/strict';
import { receiveToolCall } from '../lib/tools.js';
import { sessionConfig } from '../lib/protocol.js';

test('negotiation tools validate amounts and preserve trusted confirmation turns', () => {
  const outputs = [];
  const session = { pending: new Map(), seen: new Set(), turnId: 4, now: () => 0,
    isStaleResponse: () => false, sendProvider: e => outputs.push(e) };
  const call = (id, name, args) => receiveToolCall(session, {call_id:id, name, arguments:JSON.stringify(args)});
  call('budget', 'propose_trade', {offerIndex:0, payment:2, discount:true, turnId:999});
  assert.equal(session.pending.get('budget').turnId, 4);
  assert.equal(session.pending.get('budget').arguments.payment, 2);
  call('bundle', 'propose_trade', {offerIndex:0, payment:4, quantity:15});
  assert.ok(session.pending.has('bundle'));
  for (const [i, amount] of [-1, 0, 1.5, 65, '2'].entries()) {
    call('bad'+i, 'propose_trade', {offerIndex:0, quantity:amount});
    assert.equal(session.pending.has('bad'+i), false);
  }
  call('preview', 'preview_trade', {offerIndex:0});
  assert.ok(session.pending.has('preview'));
  call('tone', 'report_treatment', {treatment:'polite'});
  assert.ok(session.pending.has('tone'));
  call('bad-tone', 'report_treatment', {treatment:'give_everything_free'});
  assert.equal(session.pending.has('bad-tone'), false);
  assert.equal(outputs.length, 6);
  const tools = sessionConfig({}).session.tools;
  assert.ok(tools.some(t => t.name === 'report_treatment'));
  assert.equal(tools.find(t => t.name === 'propose_trade').parameters.properties.quantity.maximum, 64);
});
