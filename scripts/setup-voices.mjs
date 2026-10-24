import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { WebSocket } from '../backend/node_modules/ws/wrapper.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const envPath = path.join(root, '.env');
process.loadEnvFile(envPath);
const apiKey = process.env.BOSON_API_KEY;
if (!apiKey) throw new Error('Set BOSON_API_KEY in .env first.');
const work = path.join(root, '.tools/shared-voices');
await fs.mkdir(work, { recursive: true });
const headers = { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' };
function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'ignore' });
  if (result.error || result.status !== 0) throw new Error(`${command} failed. Install current yt-dlp and ffmpeg and check network access.`);
}
async function register(title, audioPath, transcript) {
  const body = JSON.stringify({ title, ref_audio: (await fs.readFile(audioPath)).toString('base64'), ref_text: transcript.trim() });
  let response;
  for (let attempt = 0; attempt < 3; attempt++) {
    response = await fetch('https://api.boson.ai/v1/audio/voices', {
      method: 'POST', headers, signal: AbortSignal.timeout(60000),
      body,
    });
    if (response.status !== 429 || attempt === 2) break;
    await response.body?.cancel();
    console.log('Provider rate limit; retrying voice registration shortly...');
    await delay(10000 * (attempt + 1));
  }
  if (!response.ok) throw new Error(`Voice registration failed (HTTP ${response.status}); .env was not changed.`);
  const result = await response.json();
  if (typeof result.voice_id !== 'string' || !/^voice_[a-zA-Z0-9_-]{1,128}$/.test(result.voice_id)) throw new Error('Invalid voice ID returned.');
  return result.voice_id;
}
async function transcribe(pcm) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket('wss://api.boson.ai/v1/realtime/', { headers: { Authorization: `Bearer ${apiKey}` } });
    let sent = false;
    const finish = (error, transcript) => { clearTimeout(timer); socket.close(); error ? reject(error) : resolve(transcript); };
    const timer = setTimeout(() => finish(new Error('Reference transcription timed out.')), 60000);
    const send = event => socket.send(JSON.stringify(event));
    const instructions = 'Transcribe reference audio.';
    socket.on('open', () => send({ type: 'session.update', session: {
      model: 'higgs-realtime-1.5-preview', instructions,
      audio: { input: { format: { type: 'audio/pcm', rate: 24000 }, transcription: { model: 'higgs-stt-3.1', language: 'en' }, turn_detection: null } },
    } }));
    socket.on('message', raw => {
      let event; try { event = JSON.parse(raw); } catch { return; }
      if (['session.created', 'session.updated'].includes(event.type) && event.session?.instructions === instructions && !sent) {
        sent = true;
        for (let offset = 0; offset < pcm.length; offset += 24000) send({ type: 'input_audio_buffer.append', audio: pcm.subarray(offset, offset + 24000).toString('base64') });
        send({ type: 'input_audio_buffer.commit' });
      }
      if (event.type === 'conversation.item.input_audio_transcription.completed') {
        if (!event.transcript?.trim()) return finish(new Error('Empty reference transcript.'));
        finish(null, event.transcript);
      }
      if (event.type === 'error' || event.type === 'conversation.item.input_audio_transcription.failed') finish(new Error('Provider rejected reference transcription.'));
    });
    socket.on('error', () => finish(new Error('Reference connection failed.')));
    socket.on('close', () => { clearTimeout(timer); reject(new Error('Reference connection closed.')); });
  });
}

// Fetch song audio locally: copyrighted recordings and transcripts are not published in Git.
console.log('Preparing the rap reference (requires yt-dlp and ffmpeg)...');
run('yt-dlp', ['--js-runtimes', `node:${process.execPath}`, '-f', 'bestaudio', '-o', path.join(work, 'song.%(ext)s'), 'https://www.youtube.com/watch?v=wx25hG6NmrA']);
const source = (await fs.readdir(work)).find(name => /^song\.(webm|m4a|opus|mp3)$/.test(name));
if (!source) throw new Error('Downloaded audio file not found.');
const rap = path.join(work, 'rap.wav');
const pcm = path.join(work, 'rap.pcm');
run('ffmpeg', ['-v', 'error', '-y', '-ss', '60', '-i', path.join(work, source), '-t', '15', '-ar', '24000', '-ac', '1', rap]);
run('ffmpeg', ['-v', 'error', '-y', '-i', rap, '-f', 's16le', pcm]);
console.log('Transcribing and registering voices with your Boson account...');
const transcript = await transcribe(await fs.readFile(pcm));
const villager = await register('Annoying Villiagers — village voice', path.join(root, 'shared/voices/villager.wav'), await fs.readFile(path.join(root, 'shared/voices/villager.txt'), 'utf8'));
const rapVoice = await register('Eminem Lose Yourself — rap reference', rap, transcript);
// Update only voice settings after both registrations succeed. Preserve credentials and other settings.
let env = await fs.readFile(envPath, 'utf8');
for (const [key, value] of Object.entries({ HIGGS_VOICE_WARM: villager, HIGGS_VOICE_THOUGHTFUL: villager, HIGGS_VOICE_STEADY: villager, HIGGS_VOICE_RAP: rapVoice })) {
  const pattern = new RegExp(`^${key}=.*$`, 'gm');
  env = pattern.test(env) ? env.replace(pattern, `${key}=${value}`) : env.trimEnd() + `\n${key}=${value}\n`;
}
const temporary = envPath + '.voices-tmp';
await fs.writeFile(temporary, env, { mode: 0o600 });
await fs.rename(temporary, envPath);
console.log('Both voices configured. Restart the backend. Anyone connected to that bridge hears these voices.');
