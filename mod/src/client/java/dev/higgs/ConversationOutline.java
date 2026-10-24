package dev.higgs;

/** Client-only outline around the current conversation partner. Never changes the entity's glowing flag. */
public final class ConversationOutline {
    public static final int COLOUR = 0xFFA5D8FF;
    private static volatile int partnerId = -1;
    static void set(int entityId) { partnerId = entityId; }
    static void clear() { partnerId = -1; }
    public static boolean isPartner(int entityId) { return entityId != -1 && entityId == partnerId; }
}
