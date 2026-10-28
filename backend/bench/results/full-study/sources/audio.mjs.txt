import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
const exec = promisify(execFile);
export function pcmFromWave(wave) {
  if (wave.toString('ascii', 0, 4) !== 'RIFF' || wave.toString('ascii', 8, 12) !== 'WAVE') throw new Error('Not a WAV');
  let formatOK = false, pcm;
  for (let p = 12; p + 8 <= wave.length;) {
    const size = wave.readUInt32LE(p + 4), kind = wave.toString('ascii', p, p + 4);
    if (p + 8 + size > wave.length) throw new Error('Truncated WAV');
    if (kind === 'fmt ' && size >= 16) formatOK = wave.readUInt16LE(p + 8) === 1 && wave.readUInt16LE(p + 10) === 1
      && wave.readUInt32LE(p + 12) === 24000 && wave.readUInt16LE(p + 22) === 16;
    if (kind === 'data') pcm = wave.subarray(p + 8, p + 8 + size);
    p += 8 + size + size % 2;
  }
  if (!formatOK || !pcm?.length || pcm.length % 2) throw new Error('Expected mono 24kHz PCM16 WAV');
  return pcm;
}
export async function synthesize(text) {
  const dir = await mkdtemp(join(tmpdir(), 'higgs-recovery-'));
  try {
    await exec('/usr/bin/say', ['-v', 'Samantha', '-r', '165', '-o', join(dir, 'speech.aiff'), text]);
    await exec('/usr/bin/afconvert', ['-f', 'WAVE', '-d', 'LEI16@24000', '-c', '1', join(dir, 'speech.aiff'), join(dir, 'speech.wav')]);
    const wav = await readFile(join(dir, 'speech.wav')), pcm = pcmFromWave(wav);
    return { wav, pcm, sha256: createHash('sha256').update(wav).digest('hex'), durationMs: pcm.length / 48 };
  } finally { await rm(dir, { recursive: true, force: true }); }
}

export function waveFromPcm(pcm) {
  if (pcm.length % 2) throw new Error('PCM16 must have an even byte count');
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(24000, 24); h.writeUInt32LE(48000, 28);
  h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}
