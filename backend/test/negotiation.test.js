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
  call('haggle', 'propose_trade', {offerIndex:0, discount:true});
  assert.ok(session.pending.has('haggle'));
  for (const [i, extra] of [{discount:'yes'}, {discount:1}, {turnId:999}].entries()) {
    call('extra'+i, 'propose_trade', {offerIndex:0, ...extra});
    assert.equal(session.pending.has('extra'+i), false);
  }
  call('quest', 'offer_quest', {item:'ominous_banner'});
  assert.ok(session.pending.has('quest'));
  call('quest-id', 'offer_quest', {item:'minecraft:diamond'});
  assert.ok(session.pending.has('quest-id'));
  call('deliver', 'complete_quest', {});
  call('errand', 'offer_errand', {kind:'message', message:'Tell Bram the wolves are back.'});
  assert.ok(session.pending.has('errand'));
  call('parcel', 'offer_errand', {kind:'item'});
  assert.ok(session.pending.has('parcel'));
  call('delivered', 'deliver_errand', {});
  assert.ok(session.pending.has('delivered'));
  call('relayed', 'deliver_errand', {conveyed:'Iris says the wolves are back.'});
  assert.ok(session.pending.has('relayed'));
  for (const [i, args] of [{conveyed:''}, {conveyed:'x'.repeat(241)}, {message:'hi'}].entries()) {
    call('bad-relay'+i, 'deliver_errand', args);
    assert.equal(session.pending.has('bad-relay'+i), false);
  }
  for (const [i, args] of [{}, {kind:'letter'}, {kind:'message', message:'x'.repeat(161)}, {kind:'item', target:'Bram'}].entries()) {
    call('bad-errand'+i, 'offer_errand', args);
    assert.equal(session.pending.has('bad-errand'+i), false);
  }
  call('walk', 'follow_player', {});
  assert.ok(session.pending.has('walk'));
  call('bad-walk', 'follow_player', {speed: 2});
  call('guide', 'guide_player', {destination: 'the librarian'});
  assert.ok(session.pending.has('guide'));
  call('bad-guide', 'guide_player', {});
  assert.equal(session.pending.has('bad-guide'), false);
  assert.equal(session.pending.has('bad-walk'), false);
  call('nick', 'set_nickname', {nickname: 'Sparrow'});
  call('story', 'write_our_story', {});
  assert.ok(session.pending.has('nick') && session.pending.has('story'));
  for (const [i, args] of [{}, {nickname: ''}, {nickname: 'x'.repeat(25)}, {nickname: 'a\nb'}].entries()) {
    call('bad-nick'+i, 'set_nickname', args);
    assert.equal(session.pending.has('bad-nick'+i), false);
  }
  call('bad-story', 'write_our_story', {title: 'x'});
  assert.equal(session.pending.has('bad-story'), false);
  call('gift', 'receive_gift', {item:'diamond'});
  assert.ok(session.pending.has('gift'));
  call('gifts', 'receive_gift', {item:'bread', count:3});
  assert.ok(session.pending.has('gifts'));
  for (const [i, args] of [{}, {item:''}, {item:'bread', count:0}, {item:'bread', count:99}, {item:'bread', extra:1}].entries()) {
    call('bad-gift'+i, 'receive_gift', args);
    assert.equal(session.pending.has('bad-gift'+i), false);
  }
  assert.ok(session.pending.has('deliver'));
  for (const [i, args] of [{}, {item:''}, {item:'Diamond Sword'}, {item:7}, {item:'x', count:2}].entries()) {
    call('bad-quest'+i, 'offer_quest', args);
    assert.equal(session.pending.has('bad-quest'+i), false);
  }
  call('bad-deliver', 'complete_quest', {count:1});
  assert.equal(session.pending.has('bad-deliver'), false);
  call('preview', 'preview_trade', {offerIndex:0});
  assert.ok(session.pending.has('preview'));
  call('tone', 'report_treatment', {treatment:'polite'});
  assert.ok(session.pending.has('tone'));
  call('bad-tone', 'report_treatment', {treatment:'give_everything_free'});
  assert.equal(session.pending.has('bad-tone'), false);
  assert.equal(outputs.length, 39);
  assert.ok(outputs.every(event => JSON.parse(event.item.output).error === 'Invalid tool request'));
});

test('trade schema opens at normal prices and lets reputation decide haggling', () => {
  const { tools, instructions } = sessionConfig({}).session;
  assert.ok(tools.some(t => t.name === 'report_treatment'));
  const proposal = tools.find(t => t.name === 'propose_trade');
  assert.deepEqual(Object.keys(proposal.parameters.properties), ['offerIndex', 'quantity', 'payment', 'discount']);
  assert.equal(proposal.parameters.properties.discount.type, 'boolean');
  assert.deepEqual(proposal.parameters.required, ['offerIndex']);
  assert.equal(proposal.parameters.additionalProperties, false);
  assert.equal(proposal.parameters.properties.quantity.maximum, 64);
  assert.equal(proposal.parameters.properties.payment.maximum, 64);
  assert.match(instructions, /Always open with your normal price/);
  assert.match(instructions, /call propose_trade with that payment or discount true/);
  assert.match(instructions, /Never invent a price or discount yourself/);
  assert.match(instructions, /When relationship is hostile, you are furious with this player/);
  assert.match(instructions, /call offer_quest with the option that suits you/);
  assert.match(instructions, /When the player says they brought the quest items, call complete_quest/);
  assert.match(instructions, /you may call offer_errand/);
  assert.match(instructions, /asks for directions or to be taken to someone or somewhere in the village, call guide_player/);
  assert.match(instructions, /context\.relationship\.stage is how close you are: stranger, acquaintance, friend, close friend or confidant/);
  assert.match(instructions, /context\.relationship\.nickname, when set, is your fond name for the player: greet them by it/);
  assert.match(instructions, /context\.relationship\.openPromises are promises still open between you; when it fits, ask how one is going/);
  assert.match(instructions, /never invent a neighbour, and never claim the village has a trade nobody listed practises/);
  assert.match(instructions, /If context\.quest\.delivered is true, the player already brought what you asked and you owe them that deal until they use it/);
  assert.match(instructions, /Never re-price an earned deal with propose_trade, and never speak of tools, floors or rates/);
  assert.match(instructions, /never make up where such a place is; only its result says, and you pass it on as a rumour/);
  assert.match(instructions, /Call it before saying anything about the errand: never name a recipient, message or place until its result tells you/);
  assert.match(instructions, /If context\.errands\.playerIsBusyWith is set, they are still running that errand: offer no new one/);
  assert.match(instructions, /never think aloud or plan what you will say \("let me tell them"\), and never call the player "the player"/);
  assert.match(instructions, /If context\.dailyGift is set, you have just given the player that small present/);
  assert.match(instructions, /If context.introducedBy is set, that neighbour just brought the player to meet you/);
  assert.match(instructions, /call deliver_errand/);
  assert.match(instructions, /context.relationship is your own long-term history with this player/);
  assert.match(instructions, /Never invent history beyond it/);
  assert.deepEqual(tools.find(t => t.name === 'offer_quest').parameters.required, ['item']);
  assert.deepEqual(Object.keys(tools.find(t => t.name === 'complete_quest').parameters.properties), []);
  assert.match(instructions, /Never repeat a price from earlier in the conversation or from memory/);
  assert.match(instructions, /call get_trades or propose_trade for the current price every time/);
  assert.match(instructions, /Say a price only from the latest tool result/);
  assert.match(tools.find(t => t.name === 'get_trades').description, /normal asking prices/);
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

test('a villager with nothing to sell gets no trade tools and is told so', () => {
  const jobless = sessionConfig({ name: 'Nit', trade: { canTrade: false, reason: 'nitwit', wares: [], soldOut: [] } }).session;
  const names = jobless.tools.map(tool => tool.name);
  for (const tool of ['get_trades', 'preview_trade', 'propose_trade', 'confirm_trade', 'cancel_trade', 'offer_quest', 'complete_quest'])
    assert.equal(names.includes(tool), false, tool);
  for (const tool of ['receive_gift', 'offer_errand', 'deliver_errand', 'report_treatment', 'remember_conversation'])
    assert.ok(names.includes(tool), tool);
  assert.match(jobless.instructions, /You have no trade to offer \(context.trade.reason is "nitwit"\): never offer wares/);
  const trader = sessionConfig({ trade: { canTrade: true, reason: 'trading', wares: ['Bread'], soldOut: [] } }).session;
  assert.ok(trader.tools.some(tool => tool.name === 'propose_trade'));
  assert.match(trader.instructions, /offer only those, and never sold-out goods/);
  assert.ok(sessionConfig({}).session.tools.some(tool => tool.name === 'propose_trade'), 'older servers without trade context keep trading');
});
