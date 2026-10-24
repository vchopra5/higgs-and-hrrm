package dev.higgs;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class VoicePcmBufferTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        VoicePcmBuffer buffer = new VoicePcmBuffer();
        byte[] first = new byte[6000];
        for (int i = 0; i < first.length; i++) first[i] = (byte) i;
        check(buffer.offer(first));
        ByteBuffer a = buffer.read(48000);
        check(a.isDirect() && a.remaining() == 4800);
        for (int i = 0; i < 4800; i++) check(a.get() == first[i]);
        check(buffer.queuedBytes() == 1200);
        check(buffer.offer(new byte[]{12, 34}));
        ByteBuffer b = buffer.read(4800);
        for (int i = 4800; i < 6000; i++) check(b.get() == first[i]);
        check(b.get() == 12 && b.get() == 34);
        while (b.hasRemaining()) check(b.get() == 0);
        check(buffer.queuedBytes() == 0);
        check(buffer.read(4800).remaining() == 4800); // starvation produces silence, never EOF/blocking
        byte[] full = new byte[48000 * 30];
        Arrays.fill(full, (byte) 7);
        check(buffer.offer(full));
        check(!buffer.offer(new byte[]{1, 2}));
        check(buffer.queuedBytes() == 2 && buffer.read(2).get() == 1);
        check(!buffer.offer(new byte[full.length + 2]));
        try { buffer.offer(new byte[1]); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        buffer.offer(full);
        buffer.close();
        check(buffer.isClosed() && buffer.queuedBytes() == 0 && buffer.read(4800) == null);
        buffer.offer(new byte[]{3, 4});
        check(buffer.queuedBytes() == 0);
        System.out.println("Voice PCM: packet boundaries, partial reads, starvation, overflow and cancellation passed");
    }
}
