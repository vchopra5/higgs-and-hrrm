import test from 'node:test';
import assert from 'node:assert/strict';
import { receiveToolCall } from '../lib/tools.js';
import { sessionConfig } from '../lib/protocol.js';

test('trade tools validate amounts and preserve trusted confirmation turns', () => {
  const outputs = [];
  const session = { pending: new Map(), seen: new Set(), turnId: 4, now: () => 0,
    isStaleResponse: () => false, sendProvider: e => outputs.push(e) };
  const call = (id, name, args) => receiveToolCall(session, {call_id:id, name, arguments:JSON.stringify(args)});
  call('budget', 'propose_trade', {offerIndex:0, payment:2});
  assert.equal(session.pending.get('budget').turnId, 4);
  assert.equal(session.pending.get('budget').arguments.payment, 2);
  call('bundle', 'propose_trade', {offerIndex:0, payment:4, quantity:15});
  assert.ok(session.pending.has('bundle'));
  call('default', 'propose_trade', {offerIndex:0});
  assert.ok(session.pending.has('default'));
  call('limits', 'propose_trade', {offerIndex:0, payment:1, quantity:64});
  assert.ok(session.pending.has('limits'));
  for (const key of ['quantity', 'payment']) {
    for (const [i, amount] of [-1, 0, 1.5, 65, '2'].entries()) {
      call(key+i, 'propose_trade', {offerIndex:0, [key]:amount});
      assert.equal(session.pending.has(key+i), false);
    }
  }
  for (const [i, extra] of [{discount:true}, {discount:false}, {turnId:999}].entries()) {
    call('extra'+i, 'propose_trade', {offerIndex:0, ...extra});
    assert.equal(session.pending.has('extra'+i), false);
  }
  call('preview', 'preview_trade', {offerIndex:0});
  assert.ok(session.pending.has('preview'));
  call('tone', 'report_treatment', {treatment:'polite'});
  assert.ok(session.pending.has('tone'));
  call('bad-tone', 'report_treatment', {treatment:'give_everything_free'});
  assert.equal(session.pending.has('bad-tone'), false);
  assert.equal(outputs.length, 14);
  assert.ok(outputs.every(event => JSON.parse(event.item.output).error === 'Invalid tool request'));
});

test('trade schema and instructions use only current reputation prices', () => {
  const { tools, instructions } = sessionConfig({}).session;
  assert.ok(tools.some(t => t.name === 'report_treatment'));
  const proposal = tools.find(t => t.name === 'propose_trade');
  assert.deepEqual(Object.keys(proposal.parameters.properties), ['offerIndex', 'quantity', 'payment']);
  assert.deepEqual(proposal.parameters.required, ['offerIndex']);
  assert.equal(proposal.parameters.additionalProperties, false);
  assert.equal(proposal.parameters.properties.quantity.maximum, 64);
  assert.equal(proposal.parameters.properties.payment.maximum, 64);
  assert.doesNotMatch(instructions + tools.map(tool => tool.description).join(' '), /discount|counteroffer|haggl|baseline/i);
  assert.match(instructions, /Prices depend only on the player's current reputation/);
  assert.match(instructions, /Never repeat a price from earlier in the conversation or from memory/);
  assert.match(instructions, /call get_trades or propose_trade for the current price every time/);
  assert.match(instructions, /Say a price only from the latest tool result/);
  assert.match(instructions, /Asking for a lower price does not change the price; kindness does/);
  assert.match(tools.find(t => t.name === 'get_trades').description, /current reputation/);
  assert.match(tools.find(t => t.name === 'remember_conversation').description, /Never include prices or quotes in summaries/);
});

test('fantasy speech keeps exact item names, numbers and trade consent rules', () => {
  const { instructions } = sessionConfig({}).session;
  assert.ok(instructions.includes('Speak like a villager in a fantasy realm: old-world, warm words (traveller, coin, wares, aye, fair trade, by the stars), short sentences.'));
  assert.ok(instructions.includes("Always say item names and numbers exactly as the tools give them (say 'emeralds', never invent currency names), so prices stay clear."));
  assert.match(instructions, /Use one short sentence per trading turn/);
  assert.match(instructions, /Before asking Confirm trade\?, call propose_trade/);
  assert.match(instructions, /When the player accepts an active quote, call confirm_trade immediately/);
  assert.match(instructions, /silence or unrelated speech is not consent/);
  assert.match(instructions, /call report_treatment even outside trading/);
  assert.match(instructions, /Do not report physical hits: the server handles those/);
});
