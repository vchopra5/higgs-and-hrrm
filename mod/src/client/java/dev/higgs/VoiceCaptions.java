package dev.higgs;

import java.util.ArrayList;
import java.util.List;

/** Assembles streamed transcripts into finished lines for Minecraft chat; never written to disk. */
final class VoiceCaptions {
    record Line(String speaker, String text) {}
    private static final int MAX_LENGTH = 4000;
    private String pendingId;
    private final StringBuilder pending = new StringBuilder();
    private final String[] posted = new String[2];

    /** Returns the lines completed by this event, oldest first. Villager deltas wait for their final text. */
    synchronized List<Line> update(String speaker, String id, String text, boolean replace) {
        if (!speaker.equals("you") && !speaker.equals("villager")) return List.of();
        boolean villager = speaker.equals("villager");
        List<Line> lines = new ArrayList<>();
        // An interrupted reply never gets a final; post what was heard before the next line.
        if (pendingId != null && !(villager && id.equals(pendingId))) lines.addAll(flush());
        int slot = villager ? 1 : 0;
        if (id.equals(posted[slot])) return lines;
        String clean = text.replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " ");
        if (!replace) {
            if (villager && pending.length() < MAX_LENGTH) {
                pendingId = id;
                pending.append(clean, 0, Math.min(clean.length(), MAX_LENGTH - pending.length()));
            }
            return lines;
        }
        pendingId = null;
        pending.setLength(0);
        String finished = clean.strip();
        if (finished.isEmpty()) return lines;
        posted[slot] = id;
        lines.add(new Line(speaker, finished.length() > MAX_LENGTH ? finished.substring(0, MAX_LENGTH) : finished));
        return lines;
    }

    /** Posts any partial villager reply, e.g. when the session ends mid-sentence. */
    synchronized List<Line> flush() {
        String partial = pending.toString().strip();
        String id = pendingId;
        pendingId = null;
        pending.setLength(0);
        if (partial.isEmpty()) return List.of();
        posted[1] = id;
        return List.of(new Line("villager", partial));
    }
}
