// The voice model sometimes invents prices or announces purchases without calling the trade tools.
// The server never trades on spoken words, but the player hears them; detect ungrounded trade speech so the
// villager corrects itself in the same turn.

export const TRADE_TOOLS = new Set(['get_trades', 'preview_trade', 'propose_trade', 'confirm_trade', 'cancel_trade',
  'offer_quest', 'complete_quest', 'receive_gift', 'offer_errand', 'deliver_errand', 'follow_player', 'stop_following', 'guide_player',
  'set_nickname', 'write_our_story']);

const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve',
  'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty'];
const COUNT = '(?:\\d+|' + [...ONES.slice(1), ...TENS.slice(2), 'half', 'dozen', 'a couple'].join('|') + ')';
const PRICE = new RegExp(`\\b${COUNT}\\b[^.?!]{0,30}\\bemeralds?\\b`, 'i');
const CONFIRM = /\bconfirm (?:the )?trade\b/i;
const GIFT_TAKEN = /\b(?:i'?ll (?:gladly )?take (?:it|that|this|them|your)|thank you for the (?:gift|diamond|emerald|present))\b/i;
const WARES = /\b(?:my wares|for sale|i(?:'ve| have) (?:got )?(?:some )?(?:fine |good )?(?:wares|goods) |buy (?:from me|something)|what (?:can i|would you like to) (?:sell|buy)|interested in (?:a )?trade|care to trade|want to trade)\b/i;
const NO_TRADE_FIX = ' Correct yourself briefly and kindly: you have no trade to offer. Never mention wares, prices or deals again in this conversation.';
const TRADE_FIX = ' Nothing was agreed or purchased. Briefly correct yourself, then call propose_trade for the deal the player wants '
  + 'and say only the exact terms it returns, then ask Confirm trade? Never say a trade is done unless confirm_trade returns purchased.';
const GIFT_FIX = ' Nothing was handed over yet. If the player is clearly giving you something, call receive_gift with the item they named; '
  + 'otherwise ask what they would like to give. Thank them only after receive_gift reports it.';
// "I'll be grateful when it's done" is a promise, not a finished trade.
const COMPLETED = /\b(?:done,? thanks|(?<!\b(?:when|once|until|till|after|if|before) )it'?s done|trade (?:is )?(?:done|complete)|here you (?:go|are)|pleasure doing business|enjoy your new)\b/i;

function spoken(count) {
  if (count < 20) return ONES[count];
  const tens = TENS[Math.floor(count / 10)];
  return count % 10 ? `${tens} ${ONES[count % 10]}` : tens;
}

function mentions(text, count) {
  const clean = text.toLowerCase().replace(/-/g, ' ');
  return new RegExp(`\\b(?:${count}|${spoken(count)})\\b`).test(clean);
}

/**
 * Returns why a villager response is ungrounded and how to correct it, or null.
 * Gift receipts must come from receive_gift; trade claims from the trade tools. tools: trade tools called this turn;
 * purchased: confirm_trade returned purchased this turn; quote: counts from this turn's latest quote.
 */
export function ungroundedTrade(text, { tools = new Set(), purchased = false, quote = null, trading = true } = {}) {
  if (typeof text !== 'string' || !text.trim()) return null;
  // Persona adherence: a villager with nothing to sell must not talk itself into trading.
  if (!trading && (PRICE.test(text) || WARES.test(text))) return 'You have nothing to sell, yet you spoke of wares or prices.' + NO_TRADE_FIX;
  // A delivered errand is something real changing hands; a walk beginning or ending, or a keepsake written, is a real action too.
  if (['deliver_errand', 'follow_player', 'stop_following', 'guide_player', 'write_our_story'].some(name => tools.has(name))) return null;
  if (GIFT_TAKEN.test(text) && !tools.has('receive_gift')) return 'You accepted a gift, but you have not received anything.' + GIFT_FIX;
  if (COMPLETED.test(text) && !purchased) return 'You said the trade was done, but nothing was purchased.' + TRADE_FIX;
  if (CONFIRM.test(text)) {
    if (!quote) return 'You asked the player to confirm terms the server never quoted this turn.' + TRADE_FIX;
    // A count of one may be spoken as "an axe"; larger counts must match the server's quote.
    const wrong = quote.filter(count => count > 1 && count <= 64 && !mentions(text, count));
    if (wrong.length) return 'The terms you stated do not match the server quote.' + TRADE_FIX;
  }
  if (PRICE.test(text) && ![...tools].some(name => TRADE_TOOLS.has(name))) {
    return 'You stated a price without a trade tool result this turn.' + TRADE_FIX;
  }
  return null;
}
