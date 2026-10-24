import { MERCHANT_TOOLS } from './merchant.js';
import { vocalDelivery } from './vocal-delivery.js';
import { LIMITS } from './protocol.js';

const AUDIENCES = new Set(['all', 'farmer', 'librarian', 'cleric', 'armorer', 'weaponsmith', 'toolsmith',
  'cartographer', 'fisherman', 'shepherd', 'butcher', 'leatherworker', 'mason', 'fletcher']);

export function completeToolBatch(session) {
  if (session.recording || session.cancelled || !session.responseDone
      || session.queuedResponse || session.memoryContext?.waiting
      || [...session.pending.values()].some(tool => !tool.internal) || !session.hasTools) return;

  session.hasTools = false;
  session.responseDone = false; // Prevent another response while the provider acknowledges this one.
  session.createConversationResponse();
}

function sendToolOutput(session, callId, result) {
  session.sendProvider({
    type: 'conversation.item.create',
    item: {
      type: 'function_call_output',
      call_id: callId,
      output: JSON.stringify(result),
    },
  });
}

export function submitToolResult(session, callId, result) {
  const pending = session.pending.get(callId);
  if (!pending || !session.pending.delete(callId)) return false;
  if (pending.name === 'express_emotion' && ['expressed', 'cooldown'].includes(result?.status)) {
    const delivery = vocalDelivery(pending.arguments?.emotion);
    if (delivery) result = { ...result, vocalDelivery: delivery };
  }
  session.traceTool?.(pending, result);
  session.noteToolResult?.(pending, result);
  if (pending.name === '_switch_merchant') {
    session.sendClient({ type: 'status', message: 'That villager is no longer available to trade. Nothing was purchased; move closer and ask again.' });
  }
  if (pending.memoryContext) {
    session.memoryContext.acceptResult(pending, result);
    return true;
  }
  if (pending.name === '_errand_heard') session.errandHeard?.(pending, result);
  if (pending.name === 'report_treatment') session.logTreatment?.({
    event: 'treatment_result', source: pending.internal ? 'keyword_fallback' : 'voice_model',
    callId, turnId: pending.turnId, treatment: pending.arguments?.treatment,
    status: result.status, message: result.message, error: result.error,
    relationship: result.relationship, pricePercent: result.pricePercent,
  });
  if (!pending.internal) sendToolOutput(session, callId, result);
  completeToolBatch(session);
  return true;
}

function validArguments(name, args) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) return false;
  if (name === 'share_news') return Object.keys(args).length === 1 && typeof args.memoryId === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(args.memoryId);
  if (name === 'express_emotion') return Object.keys(args).length === 1
    && ['excited', 'worried', 'angry', 'thoughtful', 'grateful'].includes(args.emotion);
  if (name === 'remember_conversation') {
    return Object.keys(args).length === 5 && typeof args.summary === 'string'
      && args.summary.trim().length > 0 && args.summary.length <= 320
      && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.summary) && typeof args.private === 'boolean'
      && Number.isInteger(args.interest) && args.interest >= 0 && args.interest <= 100
      && typeof args.reason === 'string' && args.reason.trim().length > 0 && args.reason.length <= 160
      && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.reason)
      && Array.isArray(args.audiences) && args.audiences.length >= 1 && args.audiences.length <= 4
      && args.audiences.every(audience => AUDIENCES.has(audience));
  }
  if (name === 'get_trades' || name === 'cancel_trade') return true;
  if (['complete_quest', 'follow_player', 'stop_following', 'write_our_story'].includes(name)) return Object.keys(args).length === 0;
  if (name === 'set_nickname') return Object.keys(args).length === 1 && typeof args.nickname === 'string'
    && args.nickname.trim().length > 0 && args.nickname.length <= 24 && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.nickname);
  if (name === 'guide_player') return Object.keys(args).length === 1 && typeof args.destination === 'string'
    && args.destination.trim().length > 0 && args.destination.length <= 80 && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.destination);
  if (name === 'deliver_errand') return Object.keys(args).every(key => key === 'conveyed')
    && (args.conveyed === undefined || (typeof args.conveyed === 'string' && args.conveyed.trim().length > 0
      && args.conveyed.length <= 240 && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.conveyed)));
  if (name === 'offer_errand') return Object.keys(args).every(key => ['kind', 'message'].includes(key))
    && ['message', 'item'].includes(args.kind)
    && (args.message === undefined || (typeof args.message === 'string' && args.message.trim().length > 0 && args.message.length <= 160
      && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.message)));
  if (name === 'receive_gift') return Object.keys(args).every(key => ['item', 'count'].includes(key))
    && typeof args.item === 'string' && args.item.trim().length > 0 && args.item.length <= 64 && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.item)
    && (args.count === undefined || (Number.isInteger(args.count) && args.count >= 1 && args.count <= 64));
  if (name === 'offer_quest') return Object.keys(args).length === 1 && typeof args.item === 'string' && /^[a-z0-9_:.\/-]{1,64}$/.test(args.item);
  if (name === 'confirm_trade') return typeof args.proposalId === 'string' && /^[a-f0-9-]{36}$/.test(args.proposalId);
  if (name === 'preview_trade') return Number.isInteger(args.offerIndex) && args.offerIndex >= 0;
  if (name === 'report_treatment') return ['polite', 'neutral', 'rude'].includes(args.treatment);
  return name === 'propose_trade' && Number.isInteger(args.offerIndex) && args.offerIndex >= 0
    && Object.keys(args).every(key => ['offerIndex', 'quantity', 'payment', 'discount'].includes(key))
    && ['quantity', 'payment'].every(key => args[key] === undefined || (Number.isInteger(args[key]) && args[key] >= 1 && args[key] <= 64))
    && (args.discount === undefined || typeof args.discount === 'boolean');
}

export function receiveToolCall(session, event) {
  // Late calls from an interrupted response must never reach the game server.
  if (session.isStaleResponse(event.response_id) || session.seen.has(event.call_id) || session.pending.has(event.call_id)) return;
  session.seen.add(event.call_id);
  if (session.recording || session.cancelled || session.reactionActive) {
    sendToolOutput(session, event.call_id, { error: 'Conversation interrupted' });
    return;
  }

  let args;
  try {
    args = JSON.parse(event.arguments);
  } catch {
    args = null;
  }
  if (MERCHANT_TOOLS.has(event.name) && session.floor && session.treatmentRecipient != null) {
    sendToolOutput(session, event.call_id, { error: 'The player addressed another villager. Do not act on your trade or interpret their words as your purchase consent.' });
    return;
  }
  session.hasTools = true;
  if (event.name === 'report_treatment' && args && typeof args === 'object' && !Array.isArray(args)) args.recipientEntityId = session.treatmentRecipient ?? null;
  session.noteToolCall?.(event.name);
  session.pending.set(event.call_id, {
    callId: event.call_id,
    name: event.name,
    arguments: args,
    turnId: session.turnId,
    ...(MERCHANT_TOOLS.has(event.name) && Number.isInteger(session.context?.entityId) ? { merchantEntityId: session.context.entityId } : {}),
    created: session.now(),
  });
  if (!validArguments(event.name, args)) {
    submitToolResult(session, event.call_id, { error: 'Invalid tool request' });
  } else if (event.name === 'report_treatment') {
    session.logTreatment?.({ event: 'treatment_classified', source: 'voice_model',
      callId: event.call_id, treatment: args.treatment, queued: true });
  }
}

export function interruptTools(session) {
  session.hasTools = false;
  for (const [callId, tool] of session.pending) {
    if (tool.internal) continue;
    submitToolResult(session, callId, { error: 'Conversation interrupted' });
  }
}

export function expireTools(session) {
  for (const tool of session.pending.values()) {
    if (session.now() - tool.created > LIMITS.toolMs) {
      submitToolResult(session, tool.callId, { error: 'Game server did not respond in time' });
    }
  }
}
