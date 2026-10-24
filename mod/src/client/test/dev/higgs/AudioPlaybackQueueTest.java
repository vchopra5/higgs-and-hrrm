package dev.higgs;

/** Standalone regression checks; compile with AudioPlaybackQueue.java and run with -ea. */
public final class AudioPlaybackQueueTest {
    public static void main(String[] args) throws Exception {
        AudioPlaybackQueue<byte[]> queue = new AudioPlaybackQueue<>(1000, value -> value.length);
        for (int i = 0; i < 100; i++) require(queue.offer(new byte[2]), "small packets must not hit a packet-count limit");
        require(queue.queuedBytes() == 200, "tracks PCM bytes");
        queue.clear();
        byte[] old = new byte[600], latest = new byte[600];
        require(queue.offer(old), "first chunk fits");
        require(!queue.offer(latest), "overload reports discarded speech");
        require(queue.queuedBytes() == 600, "overflow remains bounded");
        require(queue.take() == latest, "overflow retains latest pending speech");
        require(!queue.offer(new byte[1002]), "oversized packet rejected without breaking queue");
        require(queue.queuedBytes() == 0, "oversized packet consumes no memory");
        Thread waiting = Thread.ofVirtual().start(() -> {
            try { queue.take(); throw new AssertionError("take should be interrupted"); }
            catch (InterruptedException expected) { }
        });
        waiting.interrupt(); waiting.join(1000);
        require(!waiting.isAlive(), "blocked playback exits on disconnect");
        System.out.println("AudioPlaybackQueue regression checks passed");
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
