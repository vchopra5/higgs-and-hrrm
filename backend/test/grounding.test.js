import test from 'node:test';
import assert from 'node:assert/strict';
import { ungroundedTrade } from '../lib/grounding.js';

const quoted = { tools: new Set(['propose_trade']), quote: [3, 1] };

test('the reported weaponsmith conversation is caught at every invented step', () => {
  // Turn 2: a counteroffer invented without calling propose_trade.
  assert.match(ungroundedTrade("I can't go that low, but I can do two and a half emeralds for each axe. That's five emeralds total for two. Interested?"),
    /without a trade tool/);
  // Turn 6: confirmation of terms the server never quoted.
  assert.match(ungroundedTrade('Five emeralds for two iron axes. Confirm trade?'), /never quoted/);
  // Turn 7: confirm_trade returned restate_quote for 3 → 1, but the villager repeated 5 → 2.
  assert.match(ungroundedTrade('Five emeralds for two iron axes. Confirm trade?',
    { tools: new Set(['confirm_trade']), quote: [3, 1] }), /do not match/);
  // Turn 9: "Done" with no purchase.
  assert.match(ungroundedTrade('Done, thanks.'), /nothing was purchased/);
});

test('accepting a gift needs receive_gift', () => {
  // The reported conversation: the villager took the diamond only in words.
  assert.match(ungroundedTrade("A diamond? That's a fine gift, Itzahria. I'll take it. Thank you, traveller."), /received anything\. Nothing was handed over yet\. .*call receive_gift/);
  assert.match(ungroundedTrade("I'll take that diamond, Itzahria. Thank you, traveller."), /receive_gift/);
  assert.equal(ungroundedTrade("I'll take that diamond, Itzahria. Thank you, traveller.", { tools: new Set(['receive_gift']) }), null);
  assert.equal(ungroundedTrade('Oh? A gift for me? What have you got there, traveller?'), null);
  assert.doesNotMatch(ungroundedTrade('Done, thanks.'), /receive_gift/, 'trade corrections keep their own fix');
  assert.equal(ungroundedTrade("Wolves again? I'll take it from here. It's done, thank you.", { tools: new Set(['deliver_errand']) }), null);
});

test('a villager with nothing to sell is corrected when it talks of wares or prices', () => {
  assert.match(ungroundedTrade("Hello there, I've got some fine wares for you today.", { trading: false }), /nothing to sell.*you have no trade to offer/);
  assert.match(ungroundedTrade('Bread is two emeralds a loaf.', { trading: false }), /nothing to sell/);
  assert.equal(ungroundedTrade('Lovely evening for a walk, traveller.', { trading: false }), null);
  assert.equal(ungroundedTrade("I've got some fine wares for you today.", { trading: true }), null, 'traders may say so');
});

test('grounded trade speech passes', () => {
  assert.equal(ungroundedTrade('Three emeralds for one iron axe. Confirm trade?', quoted), null);
  assert.equal(ungroundedTrade('3 emeralds for an iron axe. Confirm trade?', quoted), null);
  assert.equal(ungroundedTrade('Twenty-seven sticks for one emerald. Confirm trade?', { tools: new Set(['propose_trade']), quote: [27, 1] }), null);
  assert.equal(ungroundedTrade('Done, thanks.', { tools: new Set(['confirm_trade']), purchased: true }), null);
  assert.equal(ungroundedTrade('Iron axes are three emeralds each.', { tools: new Set(['get_trades']) }), null);
  assert.equal(ungroundedTrade('Bring me a pillager banner and I will take two emeralds for the axe.', { tools: new Set(['offer_quest']) }), null);
  assert.equal(ungroundedTrade('Lovely weather today, traveller.'), null);
  // The reported errand offer: a future promise is not a finished trade.
  assert.equal(ungroundedTrade("Take this bread to Reed, the weaponsmith. I'll be grateful when it's done.", { tools: new Set(['offer_errand']) }), null);
  assert.equal(ungroundedTrade('Come back once it is done, or once it\'s done.'), null);
  assert.match(ungroundedTrade("There, it's done."), /nothing was purchased/);
  assert.equal(ungroundedTrade(''), null);
});
