import { LIMITS } from './protocol.js';

const AUDIENCES = new Set(['all', 'farmer', 'librarian', 'cleric', 'armorer', 'weaponsmith', 'toolsmith',
  'cartographer', 'fisherman', 'shepherd', 'butcher', 'leatherworker', 'mason', 'fletcher']);

export function completeToolBatch(session) {
  if (session.recording || session.cancelled || !session.responseDone
      || session.pending.size > 0 || !session.hasTools) return;

  session.hasTools = false;
  session.sendProvider({ type: 'response.create' });
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
  if (!session.pending.delete(callId)) return false;
  sendToolOutput(session, callId, result);
  completeToolBatch(session);
  return true;
}

function validArguments(name, args) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) return false;
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
  if (name === 'recall_memories') {
    return Object.keys(args).length === 2 && typeof args.query === 'string' && args.query.length <= 160
      && !/[\u0000-\u001f\u007f-\u009f]/u.test(args.query) && ['player', 'village'].includes(args.scope);
  }
  if (name === 'get_trades' || name === 'cancel_trade') return true;
  if (name === 'confirm_trade') return typeof args.proposalId === 'string' && /^[a-f0-9-]{36}$/.test(args.proposalId);
  if (name === 'preview_trade') return Number.isInteger(args.offerIndex) && args.offerIndex >= 0;
  if (name === 'report_treatment') return ['polite', 'neutral', 'rude'].includes(args.treatment);
  return name === 'propose_trade' && Number.isInteger(args.offerIndex) && args.offerIndex >= 0
    && ['quantity', 'payment'].every(key => args[key] === undefined || (Number.isInteger(args[key]) && args[key] >= 1 && args[key] <= 64))
    && (args.discount === undefined || typeof args.discount === 'boolean');
}

export function receiveToolCall(session, event) {
  // Late calls from an interrupted response must never reach the game server.
  if (session.isStaleResponse(event.response_id) || session.seen.has(event.call_id)) return;
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
  session.hasTools = true;
  session.pending.set(event.call_id, {
    callId: event.call_id,
    name: event.name,
    arguments: args,
    turnId: session.turnId,
    created: session.now(),
  });
  if (!validArguments(event.name, args)) {
    submitToolResult(session, event.call_id, { error: 'Invalid tool request' });
  }
}

export function interruptTools(session) {
  session.hasTools = false;
  for (const callId of session.pending.keys()) {
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
