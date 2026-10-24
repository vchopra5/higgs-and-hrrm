export const MERCHANT_TOOLS = new Set(['get_trades', 'preview_trade', 'propose_trade', 'confirm_trade', 'cancel_trade', 'offer_quest', 'complete_quest']);

export function requestMerchant(session, companion) {
  if (session.closed || session.recording || session.companion !== companion
      || companion.routedTurn !== session.turnId || session.resolveRecipient(session.lastTranscript?.text) !== companion.spec.entityId) return false;
  const callId = `merchant-${session.id}-${session.turnId}`;
  if (session.pending.has(callId) || session.seen.has(callId)) return false;
  session.seen.add(callId);
  session.pending.set(callId, { callId, name: '_switch_merchant', internal: true, turnId: session.turnId, created: session.now(),
    arguments: { entityId: companion.spec.entityId, request: session.lastTranscript.text.slice(0, 4000),
      recentConversation: session.recentLines.slice(-8).map(line => `${line.speaker}: ${line.text}`) } });
  return true;
}
