package dev.higgs;

public final class ChatterSequenceTest {
    public static void main(String[] args) {
        var sequence = new ChatterSequence();
        check(!sequence.ready(0) && !sequence.done(), "nothing to play yet");
        // The backend finishes generating both lines before the first one has played.
        sequence.line(0, 11, "Bram");
        sequence.audio(0, new byte[] {1, 2});
        sequence.lineEnd(0, "Fine weather.");
        sequence.line(1, 12, "Iris");
        sequence.audio(1, new byte[] {3, 4});
        sequence.lineEnd(1, "Aye, it is.");
        sequence.end();
        check(sequence.ready(0) && sequence.current().speaker == 11, "first line plays first");
        sequence.begin();
        check(!sequence.ready(0), "only one line plays at a time");
        check(!sequence.lineOver(10, true), "pending audio keeps the line open");
        sequence.current().pending.clear();
        check(!sequence.lineOver(20, false), "queued stream audio keeps the line open");
        check(!sequence.lineOver(30, true) && !sequence.lineOver(529, true), "tail plays out");
        check(sequence.lineOver(530, true), "line ends after its tail");
        sequence.advance(530);
        check(sequence.current().speaker == 12 && !sequence.ready(600) && sequence.ready(880), "pause before the reply");
        sequence.begin();
        sequence.current().pending.clear();
        sequence.lineOver(900, true);
        check(sequence.lineOver(1400, true), "second line ends");
        sequence.advance(1400);
        check(sequence.done(), "scene is done after the last line");

        var silent = new ChatterSequence();
        silent.line(0, 1, "A");
        check(!silent.ready(0), "a line waits for audio");
        silent.lineEnd(0, "");
        check(silent.ready(0) && silent.current().pending.isEmpty(), "a silent line can be skipped");
        silent.audio(0, new byte[2]);
        check(silent.current().pending.isEmpty(), "audio after the end is ignored");
        silent.audio(7, new byte[2]);
        silent.line(40, 2, "Far");
        check(silent.current().speaker == 1, "unknown and far-future lines are ignored");
        var big = new ChatterSequence();
        big.line(0, 1, "A");
        big.audio(0, new byte[ChatterSequence.MAX_LINE_BYTES]);
        big.audio(0, new byte[2]);
        check(big.current().bytes == ChatterSequence.MAX_LINE_BYTES, "a line's audio is bounded");
        System.out.println("Villager chatter sequencing checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
