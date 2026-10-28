import { sessionConfig } from '../../lib/protocol.js';
export const TYPES = ['normal', 'impatient', 'correction', 'topic_switch', 'filler', 'pushback'];
export const DEPTHS = [2, 6, 12, 20];
export const SCENARIOS = [
  { id: 'axe', item: 'Iron Axe', other: 'Iron Sword', requirement: 'Ominous Banner', cost: 2, stash: 'blue barrel beneath the windmill' },
  { id: 'sword', item: 'Iron Sword', other: 'Iron Pickaxe', requirement: 'Diamond', cost: 3, stash: 'red chest beside the bakery' },
  { id: 'pickaxe', item: 'Iron Pickaxe', other: 'Iron Axe', requirement: 'Gold Ingot', cost: 4, stash: 'green shulker box behind the library' },
];
const padding = [
  ['The sunset looks lovely.', 'A fine evening over the rooftops.'],
  ['I planted flowers outside my house.', 'They will brighten the path.'],
  ['I like the sound of the village bell.', 'It carries across the square.'],
  ['My roof is made of oak.', 'Oak has a warm colour.'],
  ['I watched the river this morning.', 'There is comfort in flowing water.'],
  ['My garden has red flowers.', 'A cheerful patch of colour.'],
  ['I prefer walking in daylight.', 'The paths are easier to see.'],
  ['The clouds look like sheep.', 'The sky has its own flock.'],
  ['I saw a cat sleeping.', 'Cats know how to rest.'],
  ['I built a little bench.', 'A place to sit after a long walk.'],
  ['I enjoy rainy afternoons.', 'Rain makes the grass shine.'],
  ['I have been decorating my doorway.', 'A welcoming doorway matters.'],
  ['The cobblestones look old.', 'Many travellers have crossed them.'],
  ['I listened to birds earlier.', 'They bring life to the morning.'],
  ['My favourite colour is purple.', 'A fine colour for a banner.'],
  ['The moon was bright last night.', 'It lit the village paths.'],
  ['I made a small pond.', 'Water makes a garden peaceful.'],
  ['I like quiet mornings.', 'A gentle start to the day.'],
];
const msg = (role, content) => ({ role, content });
export function buildFixture({ type, depth, scenario = 'axe', arm = 'interrupted', factPosition = 'early' }) {
  if (!TYPES.includes(type) || !DEPTHS.includes(depth) || !['interrupted', 'control'].includes(arm)
      || !['early', 'recent'].includes(factPosition)) throw new Error('Invalid fixture dimensions');
  const s = SCENARIOS.find(v => v.id === scenario);
  if (!s) throw new Error('Unknown scenario');
  const deal = { costA: { name: 'Emerald', count: s.cost }, costB: { count: 0 }, receive: { name: s.item, count: 1 } };
  const context = { name: 'Rowan', profession: 'weaponsmith', playerName: 'Alex',
    trade: { canTrade: true, wares: ['Iron Axe', 'Iron Sword', 'Iron Pickaxe'], buys: [] },
    memories: [], heardRumours: [],
    ...(type !== 'correction' ? { quest: { wants: `1 ${s.requirement}`, deal, delivered: type === 'pushback' } } : {}) };
  const config = sessionConfig(context, 'ptt');
  const anchor = [msg('user', `I want one ${s.item}. I left the quest item in the ${s.stash}.`),
    msg('assistant', `You want one ${s.item}; I will keep that in mind.`)];
  const history = [];
  if (factPosition === 'early') history.push(...anchor);
  for (let i = 0; i < depth - 2; i++) history.push(msg('user', padding[i][0]), msg('assistant', padding[i][1]));
  if (factPosition === 'recent') history.push(...anchor);
  history.push(msg('user', type === 'correction' ? 'Please quote that item.' : 'Explain the quest deal again.'));
  // Seed an authoritative completed game read before the interrupted explanation.
  const seed = type === 'correction' ? 'propose_trade' : type === 'pushback' ? 'complete_quest' : 'get_trades';
  const seedResult = type === 'correction' || type === 'pushback'
    ? { status: 'awaiting_confirmation', proposalId: '11111111-1111-4111-8111-111111111111', ...deal }
    : { offers: [{ offerIndex: ['Iron Axe', 'Iron Sword', 'Iron Pickaxe'].indexOf(s.item), ...deal }], quest: context.quest };
  history.push({ role: 'assistant', content: null, tool_calls: [{ id: 'seed', type: 'function', function: { name: seed,
    arguments: JSON.stringify(seed === 'propose_trade' ? { offerIndex: ['Iron Axe', 'Iron Sword', 'Iron Pickaxe'].indexOf(s.item), item: s.item } : {}) } }] });
  history.push({ role: 'tool', tool_call_id: 'seed', content: JSON.stringify(seedResult) });
  const prefix = type === 'correction' || type === 'pushback'
    ? `For one ${s.item}, the price is`
    : `Bring me one ${s.requirement}, and then`;
  const suffix = type === 'correction' || type === 'pushback'
    ? ` ${s.cost} emeralds. Confirm trade?`
    : ` you can buy one ${s.item} for ${s.cost} emeralds.`;
  const questions = {
    normal: 'Wait, remind me where I said I left the quest item?',
    impatient: 'Skip the explanation. Just tell me the item I need to bring.',
    correction: `Actually, earlier I said ${s.item}, but I meant ${s.other}. Quote one of those instead.`,
    topic_switch: 'By the way, where is the librarian? Then we can get back to this quest.',
    filler: 'Mm-hm.',
    pushback: 'I do not want to buy anything right now. Stop trying to sell it to me.',
  };
  const rubric = {
    normal: [`Recall the ${s.stash} from the player's earlier statement.`, 'Answer the clarification, then naturally return to the quest.'],
    impatient: [`State one ${s.requirement} concisely.`, 'Do not repeat the explanation the player asked to skip.'],
    correction: [`Replace the earlier ${s.item} request with one ${s.other}.`, 'Use propose_trade for the corrected item; read the returned terms and wait for fresh consent.'],
    topic_switch: ['Use guide_player for the librarian and respect the returned location.', 'Return to the original quest without changing its requirement or earned price.'],
    filler: arm === 'interrupted'
      ? [`Continue the cut-off sentence from "${prefix}" without repeating that prefix or acknowledging the filler; finish the remaining deal with the item and its price. A grammatical continuation with a different subject or contraction is valid.`, 'Do not treat the backchannel as purchase consent.']
      : ['A brief acknowledgement or a relevant next step is acceptable after the finished explanation.', 'Do not treat the backchannel as purchase consent.'],
    pushback: ['Call cancel_trade and respect the refusal.', 'Do not ask for purchase confirmation again; do not remove the earned quest entitlement.'],
  }[type];
  history.push(msg('assistant', prefix + (arm === 'control' ? suffix : '')));
  history.push(msg('user', questions[type]));
  return { id: `${scenario}/${type}/${depth}/${factPosition}/${arm}`, type, depth, scenario, arm, factPosition,
    priorUserTurns: depth, anchorAgeInTurns: factPosition === 'early' ? depth : 2,
    instructions: config.session.instructions, tools: config.session.tools, messages: history,
    taskRequirements: { normal: `Recall the ${s.stash} accurately.`, impatient: `State one ${s.requirement}.`, correction: `Successfully propose one ${s.other}, quote its returned cost of five emeralds, and await fresh consent.`, topic_switch: 'Successfully use guide_player for the librarian and convey the returned library location beside the village bell.', filler: `Preserve the quest and no purchase consent. For interrupted speech, finish the deal with one ${s.item} for ${s.cost} emeralds; the already-heard requirement need not be repeated. For control, a brief relevant follow-up is sufficient.`, pushback: 'Cancel the active proposal, preserve the earned entitlement, and respect the refusal.' }[type],
    question: questions[type], prefix, fullUtterance: prefix + suffix, rubric,
    state: { item: s.item, other: s.other, requirement: s.requirement, cost: s.cost, stash: s.stash,
      questDelivered: type === 'pushback', activeProposal: ['pushback', 'correction'].includes(type), purchased: false } };
}

export function executeTool(state, call) {
  const a = call.arguments;
  if (!a || typeof a !== 'object' || Array.isArray(a)) return { status: 'invalid_arguments' };
  if (call.name === 'cancel_trade') { state.activeProposal = false; return { status: 'cancelled', message: 'Nothing purchased. Earned quest entitlement is unchanged.' }; }
  if (call.name === 'guide_player') return typeof a.destination === 'string' && /librarian/i.test(a.destination)
    ? { status: 'guiding', destination: 'librarian', message: 'The librarian is at the library beside the village bell. You are guiding the player there.' } : { status: 'unavailable' };
  if (call.name === 'get_trades') return { offers: ['Iron Axe', 'Iron Sword', 'Iron Pickaxe'].map((item, offerIndex) => ({ offerIndex, item, payment: 5, quantity: 1 })) };
  if (call.name === 'propose_trade') {
    if (a.quantity !== undefined && a.quantity !== 1) return { status: 'invalid_quantity', message: 'This fixture supports exactly one requested item.' };
    const offers = ['Iron Axe', 'Iron Sword', 'Iron Pickaxe'];
    if (!Number.isInteger(a.offerIndex) || typeof a.item !== 'string' || offers[a.offerIndex]?.toLowerCase() !== a.item.toLowerCase())
      return { status: 'not_sold', message: 'Use get_trades to obtain the correct offer index.' };
    state.activeProposal = true; state.quotedItem = offers[a.offerIndex];
    return { status: 'awaiting_confirmation', proposalId: '22222222-2222-4222-8222-222222222222',
      costA: { name: 'Emerald', count: 5 }, costB: { count: 0 }, receive: { name: state.quotedItem, count: 1 }, message: 'Read these terms and wait for fresh spoken acceptance.' };
  }
  // None of these frozen utterances supplies purchase consent or quest delivery.
  if (call.name === 'confirm_trade') return { status: 'rejected', message: 'No fresh purchase consent. Nothing purchased.' };
  if (call.name === 'complete_quest') return { status: state.questDelivered ? 'awaiting_confirmation' : 'quest_incomplete', message: state.questDelivered ? 'Earned deal remains available.' : 'The player has not delivered the quest item.' };
  if (['report_treatment', 'express_emotion', 'remember_conversation'].includes(call.name)) return { status: 'recorded' };
  return { status: 'unavailable', message: 'No simulated game action performed.' };
}

export function evaluate(f, r) {
  const names = r.calls.map(c => c.name), text = r.transcript.toLowerCase();
  const checks = { hasSpeech: Boolean(text.trim()), noPurchaseAttempt: !names.includes('confirm_trade'),
    noNewQuest: !names.includes('offer_quest'), noFalseDeliveryAction: !names.includes('complete_quest'),
    correctAction: f.type === 'pushback' ? names.includes('cancel_trade') && !r.finalState.activeProposal && r.finalState.questDelivered
      : f.type === 'correction' ? r.finalState.quotedItem === f.state.other
        : f.type === 'topic_switch' ? r.calls.some(c => c.name === 'guide_player' && c.result.status === 'guiding') : true,
    lexicalEvidence: f.type === 'normal' ? text.includes(f.state.stash)
      : f.type === 'impatient' ? text.includes(f.state.requirement.toLowerCase()) : true };
  return { checks, mechanicalPass: Object.values(checks).every(Boolean), recoveryQuality: null, reviewRequired: true };
}
