package dev.higgs;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class PcmAudioConverterTest {
    public static void main(String[] args) {
        byte[] signal = new byte[4800 * 4];
        for (int i = 0; i < signal.length / 4; i++) {
            int left = (int) (12000 * Math.sin(i * 0.03));
            put(signal, i * 4, left);
            put(signal, i * 4 + 2, -left / 2);
        }
        for (int inputRate : new int[] {24000, 44100, 48000}) {
          for (int rate : new int[] {24000, 44100, 48000}) {
            for (int channels : new int[] {1, 2}) {
                PcmAudioConverter whole = new PcmAudioConverter(inputRate, 2, rate, channels);
                byte[] expected = whole.convert(signal, signal.length);
                PcmAudioConverter chunked = new PcmAudioConverter(inputRate, 2, rate, channels);
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                for (int start = 0; start < signal.length;) {
                    int count = Math.min(4 * 137, signal.length - start);
                    byte[] part = Arrays.copyOfRange(signal, start, start + count);
                    actual.writeBytes(chunked.convert(part, part.length));
                    start += count;
                }
                check(Arrays.equals(expected, actual.toByteArray()), "chunk boundaries " + rate + "/" + channels);
                int actualFrames = expected.length / (channels * 2);
                check(Math.abs(actualFrames - (long) 4800 * rate / inputRate) <= 1, "sample duration " + rate);
                chunked.reset();
                check(Arrays.equals(expected, chunked.convert(signal, signal.length)), "reset after interrupted turn");
            }
          }
        }
        byte[] constant = new byte[2400 * 2];
        for (int i = 0; i < 2400; i++) put(constant, i * 2, -12345);
        for (int rate : new int[] {44100, 48000}) {
            byte[] expanded = new PcmAudioConverter(24000, 1, rate, 2).convert(constant, constant.length);
            check(Math.abs(expanded.length / 4 - rate / 10) <= 1, "playback duration " + rate);
            for (int i = 0; i < expanded.length; i += 4) {
                check(sample(expanded, i) == -12345 && sample(expanded, i + 2) == -12345, "signed stereo duplicate");
            }
        }
        byte[] endpoints = {0, -128, -1, 127, -1, -1, 0, 0};
        check(Arrays.equals(endpoints, new PcmAudioConverter(24000, 1, 24000, 1).convert(endpoints, endpoints.length)), "24 kHz identity");
        PcmAudioConverter stereo = new PcmAudioConverter(48000, 2, 24000, 1);
        try { stereo.convert(new byte[3], 3); throw new AssertionError("incomplete frame accepted"); }
        catch (IllegalArgumentException expected) { }
        check(stereo.convert(new byte[0], 0).length == 0, "empty input");
        System.out.println("PCM audio conversion tests passed");
    }

    private static void put(byte[] bytes, int offset, int value) { bytes[offset] = (byte) value; bytes[offset + 1] = (byte) (value >> 8); }
    private static int sample(byte[] bytes, int offset) { return (short) ((bytes[offset] & 255) | (bytes[offset + 1] << 8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
