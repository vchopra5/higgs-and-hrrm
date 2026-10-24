package dev.higgs;

import java.util.Arrays;

/** Stateful PCM16 LE mono/stereo conversion; interpolation continues across network/device chunks. */
final class PcmAudioConverter {
    private final int inputRate, inputChannels, outputRate, outputChannels;
    private long framesSeen, nextOutput;
    private int previous;

    PcmAudioConverter(int inputRate, int inputChannels, int outputRate, int outputChannels) {
        if (inputRate < 8000 || inputRate > 192000 || outputRate < 8000 || outputRate > 192000
            || inputChannels < 1 || inputChannels > 2 || outputChannels < 1 || outputChannels > 2)
            throw new IllegalArgumentException("Unsupported PCM format");
        this.inputRate = inputRate;
        this.inputChannels = inputChannels;
        this.outputRate = outputRate;
        this.outputChannels = outputChannels;
    }

    void reset() { framesSeen = 0; nextOutput = 0; previous = 0; }

    byte[] convert(byte[] input, int length) {
        if (length < 0 || length > input.length || length % (inputChannels * 2) != 0)
            throw new IllegalArgumentException("Incomplete PCM frame");
        int frames = length / (inputChannels * 2);
        byte[] output = new byte[(int) (((long) frames * outputRate + inputRate - 1) / inputRate + 2) * outputChannels * 2];
        int written = 0;
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * inputChannels * 2;
            int sample = sample(input, offset);
            if (inputChannels == 2) sample = (sample + sample(input, offset + 2)) / 2;
            long position = framesSeen * outputRate;
            if (framesSeen == 0) previous = sample;
            while (nextOutput <= position) {
                long fraction = framesSeen == 0 ? outputRate : nextOutput - (position - outputRate);
                int value = previous + (int) ((sample - previous) * fraction / outputRate);
                for (int channel = 0; channel < outputChannels; channel++) {
                    output[written++] = (byte) value;
                    output[written++] = (byte) (value >> 8);
                }
                nextOutput += inputRate;
            }
            previous = sample;
            framesSeen++;
        }
        return Arrays.copyOf(output, written);
    }

    private static int sample(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 255) | (bytes[offset + 1] << 8));
    }
}
