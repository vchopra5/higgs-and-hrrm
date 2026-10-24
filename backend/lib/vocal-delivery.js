// Higgs Realtime controls delivery through instructions (not TTS-only emotion parameters).
export const EXPRESSIVE_DELIVERY = 'Act the emotion in your voice, not just in your choice of words. '
  + 'Let current events, what you actually know, your mood and relationship shape audible changes in pace, pitch, emphasis, pauses and energy. '
  + 'Good news may bring quick, bright excitement; danger a tense, urgent or hushed voice; being hit a sharp, hurt or angry reaction; '
  + 'kindness a warm, softened voice; uncertainty a thoughtful pause. Choose the feeling yourself from the evidence; do not invent danger or excitement. '
  + 'Your usual personality is a baseline, not a requirement to sound the same on every turn. Ordinary conversation can settle back to ease. '
  + 'Keep the same character and voice identity. Do not shout constantly, narrate your acting, say emotion labels, or speak stage directions. '
  + 'If express_emotion returns vocalDelivery, use it for the next reply only, without reading it aloud; later replies follow their own context. ';

const deliveries = Object.freeze({
  excited: 'For the next reply, let genuine excitement be audible: brighter pitch variation, lively emphasis, a quickened pace and a small breath of surprise. Keep words clear.',
  worried: 'For the next reply, sound concerned: tense and intent, with brief hesitant pauses or urgent emphasis appropriate to the actual danger. Do not sound cheerful.',
  angry: 'For the next reply, let displeasure be audible: firmer attacks, clipped phrasing and a sharper edge. Scale intensity to the mistreatment; anger need not mean shouting.',
  thoughtful: 'For the next reply, sound reflective: measured pace, a brief thinking pause and questioning intonation where you are unsure. Do not become flat or sleepy.',
  grateful: 'For the next reply, sound warmly appreciative: soften the tone, ease the pace and put sincere emphasis on the thanks, without an exaggerated performance.',
});
export function vocalDelivery(emotion) {
  return typeof emotion === 'string' && Object.hasOwn(deliveries, emotion) ? deliveries[emotion] : null;
}
