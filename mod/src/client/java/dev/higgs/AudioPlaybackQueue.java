package dev.higgs;

import java.util.ArrayDeque;
import java.util.function.ToIntFunction;

/** Bounds PCM backlog by duration in bytes, independent of provider packet sizes. */
final class AudioPlaybackQueue<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final int limit;
    private final ToIntFunction<T> size;
    private int bytes;

    AudioPlaybackQueue(int limit, ToIntFunction<T> size) {
        this.limit = limit;
        this.size = size;
    }

    /** Drops oldest pending chunks on overload. False reports that speech was skipped. */
    synchronized boolean offer(T value) {
        int count = size.applyAsInt(value);
        if (count > limit) return false;
        boolean intact = true;
        while (bytes + count > limit && !queue.isEmpty()) {
            bytes -= size.applyAsInt(queue.removeFirst());
            intact = false;
        }
        queue.addLast(value);
        bytes += count;
        notifyAll();
        return intact;
    }

    synchronized T take() throws InterruptedException {
        while (queue.isEmpty()) wait();
        T value = queue.removeFirst();
        bytes -= size.applyAsInt(value);
        return value;
    }

    synchronized void clear() { queue.clear(); bytes = 0; }
    synchronized int queuedBytes() { return bytes; }
}
