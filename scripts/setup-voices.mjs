import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const root = fileURLToPath(new URL('../', import.meta.url));
const envPath = path.join(root, '.env');
process.loadEnvFile(envPath);
const apiKey = process.env.BOSON_API_KEY;
if (!apiKey) throw new Error('Set BOSON_API_KEY in .env first.');
const headers = { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' };
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
console.log('Registering the villager voice with your Boson account...');
const villager = await register('Annoying Villiagers — village voice', path.join(root, 'shared/voices/villager.wav'), await fs.readFile(path.join(root, 'shared/voices/villager.txt'), 'utf8'));
// Preserve credentials and other settings.
let env = await fs.readFile(envPath, 'utf8');
for (const [key, value] of Object.entries({ HIGGS_VOICE_WARM: villager, HIGGS_VOICE_THOUGHTFUL: villager, HIGGS_VOICE_STEADY: villager, HIGGS_VOICE_RAP: '' })) {
  const pattern = new RegExp(`^${key}=.*$`, 'gm');
  env = pattern.test(env) ? env.replace(pattern, `${key}=${value}`) : env.trimEnd() + `\n${key}=${value}\n`;
}
const temporary = envPath + '.voices-tmp';
await fs.writeFile(temporary, env, { mode: 0o600 });
await fs.rename(temporary, envPath);
console.log('Villager voice configured for conversations and raps. Restart the backend. Anyone connected to that bridge hears these voices.');
