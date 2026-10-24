package dev.higgs;

import java.util.ArrayList;
import java.util.List;

/** Ephemeral captions only: two bounded messages, never written to logs or disk. */
final class VoiceCaptions {
    record Caption(String speaker, String id, String text, long updated) {
        float opacity(long now) { return Math.max(0, Math.min(1, (11_000 - (now - updated)) / 3000f)); }
    }
    private final Caption[] messages = new Caption[2];
    synchronized void update(String speaker, String id, String text, boolean replace, long now) {
        if (!speaker.equals("you") && !speaker.equals("villager")) return;
        int slot = speaker.equals("you") ? 0 : 1;
        Caption previous = messages[slot];
        String clean = text.replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " ");
        String combined = !replace && previous != null && previous.id().equals(id) ? previous.text() + clean : clean;
        if (combined.isBlank()) return;
        if (combined.length() > 1200) combined = combined.substring(combined.length() - 1200);
        messages[slot] = new Caption(speaker, id, combined, now);
    }
    synchronized List<Caption> visible(long now) {
        List<Caption> result = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            if (messages[i] != null && messages[i].opacity(now) > 0) result.add(messages[i]);
            else messages[i] = null;
        }
        return result;
    }
}
