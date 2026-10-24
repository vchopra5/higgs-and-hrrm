package dev.higgs;

public final class PlaybackCompletionTest {
    public static void main(String[] args) {
        PlaybackCompletion p = new PlaybackCompletion();
        check(p.poll(false, 0) == -1, "no response end");
        p.end(7);
        check(p.poll(true, 100) == -1, "queued PCM blocks acknowledgement");
        check(p.poll(false, 200) == -1, "native audio still buffered");
        check(p.poll(false, 699) == -1, "tail not drained");
        check(p.poll(true, 700) == -1, "new companion PCM resets tail");
        check(p.poll(false, 800) == -1, "new tail");
        check(p.poll(false, 1300) == 7, "exact turn acknowledged");
        check(p.poll(false, 1800) == -1, "acknowledge once");
        p.end(8); p.poll(false, 2000); p.clear();
        check(p.poll(false, 3000) == -1, "B cancels pending acknowledgement");
        p.end(9);
        check(p.poll(false, 3000) == -1, "fresh response requires fresh tail");
        check(p.poll(false, 3500) == 9, "new response survives old cancellation");
        System.out.println("Playback completion checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
