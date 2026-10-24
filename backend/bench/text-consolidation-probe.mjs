// Live probe: does Higgs Realtime accept a text-only session, and can it return a JSON conversation digest?
// Phase 3 consolidation depends on this; without it, long-term memory stays on server-observed facts.
// Usage: node bench/text-consolidation-probe.mjs [results/text-consolidation-probe.json]
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

const root = fileURLToPath(new URL('../..', import.meta.url));
try { process.loadEnvFile(resolve(root, '.env')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
const output = resolve(fileURLToPath(new URL('.', import.meta.url)), process.argv[2] ?? 'results/text-consolidation-probe.json');
const url = process.env.HIGGS_REALTIME_URL || 'wss://api.boson.ai/v1/realtime/';

const transcript = [
  'Steve: Hello Iris, any news?',
  'Iris: Wolves were seen near the eastern fields, traveller.',
  'Steve: I will deal with them tomorrow, I promise.',
  'Steve: Could I buy two iron axes?',
  'Iris: Five emeralds for two iron axes. Confirm trade?',
  'Steve: Yes.',
  'Iris: Done, thanks.',
];
const request = 'You are Iris, a Minecraft villager, consolidating a conversation that just ended into your own long-term memory. '
  + 'Previous summary of your relationship with Steve: "Steve is a polite regular who once gave me a diamond." '
  + 'Events the server saw: "Steve bought 2 × Iron Axe from me for 5 × Emerald". Transcript:\n' + transcript.join('\n')
  + '\n\nReply with only a JSON object, no prose: {"digest": "at most two first-person sentences about what mattered", '
  + '"summary": "the updated relationship summary, at most 600 characters, first person", "promises": ["things either side promised"]}';

const arms = [
  { name: 'text_only', session: { output_modalities: ['text'] } },
  { name: 'text_and_audio', session: { output_modalities: ['text', 'audio'] } },
];

function probe(arm) {
  return new Promise(resolveArm => {
    const started = Date.now();
    const result = { arm: arm.name, events: [], errors: [], text: '', transcript: '', audioBytes: 0, ms: 0 };
    const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${process.env.BOSON_API_KEY}` } });
    let asked = false;
    const done = () => { result.ms = Date.now() - started; try { ws.close(); } catch { /* closed */ } resolveArm(result); };
    const timer = setTimeout(() => { result.errors.push('timeout'); done(); }, 30_000);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'session.update', session: {
      model: 'higgs-realtime', instructions: 'You consolidate memories for a Minecraft villager. Reply only with the JSON requested.',
      ...arm.session,
      audio: { input: { format: { type: 'audio/pcm', rate: 24000 }, turn_detection: null },
        output: { format: { type: 'audio/pcm', rate: 24000 }, voice: 'oliver' } },
    } })));
    ws.on('message', data => {
      const event = JSON.parse(data);
      if (!result.events.includes(event.type)) result.events.push(event.type);
      if (event.type === 'error') { result.errors.push(event.error?.message ?? event.error?.code ?? 'error'); clearTimeout(timer); done(); return; }
      if ((event.type === 'session.created' || event.type === 'session.updated') && !asked) {
        asked = true;
        result.sessionModalities = event.session?.output_modalities ?? null;
        ws.send(JSON.stringify({ type: 'conversation.item.create', item: { type: 'message', role: 'user', content: [{ type: 'input_text', text: request }] } }));
        ws.send(JSON.stringify({ type: 'response.create' }));
      }
      if (/output_text\.delta$|response\.text\.delta$/.test(event.type) && typeof event.delta === 'string') result.text += event.delta;
      if (/output_text\.done$|response\.text\.done$/.test(event.type) && typeof event.text === 'string') result.text = event.text;
      if (event.type === 'response.output_audio_transcript.done') result.transcript = event.transcript ?? '';
      if (event.type === 'response.output_audio.delta' && typeof event.delta === 'string') result.audioBytes += Buffer.from(event.delta, 'base64').length;
      if (event.type === 'response.done') { result.status = event.response?.status; clearTimeout(timer); done(); }
    });
    ws.on('error', error => { result.errors.push(String(error.message)); clearTimeout(timer); done(); });
  });
}

function parsed(text) {
  const match = String(text).match(/\{[\s\S]*\}/);
  try { return match ? JSON.parse(match[0]) : null; } catch { return null; }
}

const results = [];
for (const arm of arms) {
  const result = await probe(arm);
  const json = parsed(result.text) ?? parsed(result.transcript);
  result.json = json;
  result.validDigest = Boolean(json && typeof json.digest === 'string' && typeof json.summary === 'string' && Array.isArray(json.promises));
  results.push(result);
  console.log(`${arm.name}: status=${result.status ?? '-'} errors=${result.errors.join('; ') || 'none'} text=${result.text.length} chars `
    + `transcript=${result.transcript.length} chars audio=${result.audioBytes} B validDigest=${result.validDigest} ${result.ms} ms`);
  if (json) console.log('  ', JSON.stringify(json));
}
await mkdir(dirname(output), { recursive: true });
await writeFile(output, JSON.stringify({ probedAt: new Date().toISOString(), url, results }, null, 2) + '\n');
console.log('saved', output);
