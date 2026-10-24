// The voice model sometimes invents prices or announces purchases without calling the trade tools.
// The server never trades on spoken words, but the player hears them; detect ungrounded trade speech so the
// villager corrects itself in the same turn.

export const TRADE_TOOLS = new Set(['get_trades', 'preview_trade', 'propose_trade', 'confirm_trade', 'cancel_trade',
  'offer_quest', 'complete_quest']);

const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve',
  'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty'];
const COUNT = '(?:\\d+|' + [...ONES.slice(1), ...TENS.slice(2), 'half', 'dozen', 'a couple'].join('|') + ')';
const PRICE = new RegExp(`\\b${COUNT}\\b[^.?!]{0,30}\\bemeralds?\\b`, 'i');
const CONFIRM = /\bconfirm (?:the )?trade\b/i;
const COMPLETED = /\b(?:done,? thanks|it'?s done|trade (?:is )?(?:done|complete)|here you (?:go|are)|pleasure doing business|enjoy your new)\b/i;

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
 * Returns why a villager response is ungrounded, or null. tools: trade tools called this turn;
 * purchased: confirm_trade returned purchased this turn; quote: counts from this turn's latest quote.
 */
export function ungroundedTrade(text, { tools = new Set(), purchased = false, quote = null } = {}) {
  if (typeof text !== 'string' || !text.trim()) return null;
  if (COMPLETED.test(text) && !purchased) return 'You said the trade was done, but nothing was purchased.';
  if (CONFIRM.test(text)) {
    if (!quote) return 'You asked the player to confirm terms the server never quoted this turn.';
    // A count of one may be spoken as "an axe"; larger counts must match the server's quote.
    const wrong = quote.filter(count => count > 1 && count <= 64 && !mentions(text, count));
    if (wrong.length) return 'The terms you stated do not match the server quote.';
  }
  if (PRICE.test(text) && ![...tools].some(name => TRADE_TOOLS.has(name))) {
    return 'You stated a price without a trade tool result this turn.';
  }
  return null;
}
