package dev.higgs;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Nonblocking, bounded mono PCM queue. Silence keeps a live stream alive between packets. */
final class VoicePcmBuffer {
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int offset, queued;
    private boolean closed;
    synchronized boolean offer(byte[] pcm) {
        if (closed) return true;
        if ((pcm.length & 1) != 0) throw new IllegalArgumentException("Unaligned PCM");
        if (pcm.length > 48000 * 30) return false;
        boolean intact = true;
        while (queued + pcm.length > 48000 * 30 && !chunks.isEmpty()) {
            queued -= chunks.removeFirst().length - offset;
            offset = 0;
            intact = false;
        }
        if (pcm.length > 0) { chunks.addLast(pcm); queued += pcm.length; }
        return intact;
    }
    synchronized ByteBuffer read(int requested) {
        if (closed) return null;
        // Four native buffers provide 400 ms of headroom for Minecraft's sound ticks.
        int size = Math.min(requested, 4800) & ~1;
        ByteBuffer result = ByteBuffer.allocateDirect(size);
        while (result.hasRemaining() && !chunks.isEmpty()) {
            byte[] head = chunks.peekFirst();
            int count = Math.min(result.remaining(), head.length - offset);
            result.put(head, offset, count);
            queued -= count;
            offset += count;
            if (offset == head.length) { chunks.removeFirst(); offset = 0; }
        }
        while (result.hasRemaining()) result.put((byte) 0);
        return result.flip();
    }
    synchronized int queuedBytes() { return queued; }
    synchronized boolean isClosed() { return closed; }
    synchronized void close() { closed = true; chunks.clear(); queued = offset = 0; }
}
