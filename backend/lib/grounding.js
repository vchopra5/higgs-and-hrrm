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

// Claiming stock: "I've got wheat seeds right here", "plenty of seed in stock".
const CLAIM = /\b(?:i'?ve got|i have|i got|got some|i sell|i can sell|i stock|we have|we'?ve got|plenty of)\b([^.?!]{0,40})|([^.?!]{0,40})\bin stock\b/gi;
// The player asking for something: only then is a matching claim about an item.
const ASKING = /\b(?:do you (?:have|sell|stock)|have you got|got any|you got any|any .{0,20} for sale|looking (?:for|to buy)|i (?:want|need|would like|'d like) (?:to buy|some|a|an)|can i (?:get|buy|have)|buy|sell me)\b/i;
const FILLER = new Set(['a', 'an', 'the', 'some', 'any', 'and', 'or', 'of', 'for', 'to', 'in', 'on', 'at', 'with', 'my', 'your', 'you', 'i', 'me',
  'we', 'it', 'that', 'this', 'there', 'here', 'right', 'just', 'got', 'have', 'do', 'does', 'is', 'are', 'be', 'can', 'get', 'buy', 'sell',
  'want', 'need', 'like', 'looking', 'today', 'now', 'fresh', 'fine', 'good', 'plenty', 'stock', 'ware', 'good', 'emerald', 'trade', 'deal',
  'price', 'minute', 'moment', 'time', 'question', 'idea', 'word', 'news', 'story', 'what', 'how', 'much', 'many', 'few', 'lot', 'thing']);

/** Same loose stemming as the game server's TextTerms: "seeds" and "seed" compare equal. */
function stem(word) {
  if (word.length > 4 && word.endsWith('ves')) return word.slice(0, -3) + 'f';
  if (word.length > 4 && word.endsWith('ies')) return word.slice(0, -3) + 'y';
  if (word.length > 4 && word.endsWith('ing')) return word.slice(0, -3);
  if (word.length > 3 && word.endsWith('ed') && !word.endsWith('eed')) return word.slice(0, -2);
  if (word.length > 3 && word.endsWith('s') && !word.endsWith('ss')) return word.slice(0, -1);
  return word;
}

function stems(text) {
  return new Set(String(text).toLowerCase().replace(/(?<=\p{L})['’]s\b/gu, '').split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean).map(stem).filter(word => !FILLER.has(word)));
}

/**
 * The words of something the player asked for that the villager claims to have, though none of its wares are called
 * that. "Do you have wheat seeds?" / "I've got wheat seeds right here" from a farmer selling bread and apples → "wheat seed".
 */
export function unstockedClaim(text, said, wares) {
  if (!Array.isArray(wares) || typeof said !== 'string' || !ASKING.test(said)) return null;
  const asked = stems(said), stocked = new Set(wares.flatMap(name => [...stems(name)]));
  const claimed = new Set();
  for (const match of String(text).matchAll(CLAIM))
    for (const word of stems(match[1] ?? match[2] ?? '')) if (asked.has(word) && !stocked.has(word)) claimed.add(word);
  return claimed.size ? [...claimed].join(' ') : null;
}

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
export function ungroundedTrade(text, { tools = new Set(), purchased = false, quote = null, trading = true, said = null, wares = null } = {}) {
  if (typeof text !== 'string' || !text.trim()) return null;
  // Persona adherence: a villager with nothing to sell must not talk itself into trading.
  if (!trading && (PRICE.test(text) || WARES.test(text))) return 'You have nothing to sell, yet you spoke of wares or prices.' + NO_TRADE_FIX;
  // Checked even after a trade tool: a not_sold result does not stop the model repeating the claim.
  const claimed = trading ? unstockedClaim(text, said, wares) : null;
  if (claimed) return `You said you have ${claimed}, but you sell nothing like it: your wares are ${wares.join(', ') || 'none right now'}. `
    + 'Correct yourself plainly: you have none. Do not offer something else in its place unless they ask.';
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
