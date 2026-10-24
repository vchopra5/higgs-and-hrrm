package dev.higgs;

/** RMS measurement only; never retains microphone samples. */
final class AudioLevels {
    static double dbfs(byte[] pcm, int length) {
        int samples = Math.min(length, pcm.length) / 2;
        if (samples == 0) return -96;
        double squares = 0;
        for (int i = 0; i < samples * 2; i += 2) {
            short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            double normalized = sample / 32768.0;
            squares += normalized * normalized;
        }
        return Math.max(-96, 10 * Math.log10(squares / samples));
    }
}
