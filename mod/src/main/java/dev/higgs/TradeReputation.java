package dev.higgs;

/** Persistent player/villager opinion. Timestamps prevent rapid farming of changes. */
record TradeReputation(int score, long updated, long lastHit) {
    static TradeReputation neutral() { return new TradeReputation(0, 0, 0); }
    TradeReputation hit(long now) {
        if (lastHit != 0 && now - lastHit < 750) return this;
        return new TradeReputation(Math.max(-5, score - 2), now, now);
    }
    TradeReputation treatment(String tone, long now) {
        // No cooldown: every polite or rude turn counts. HiggsVillagers assesses each turn once.
        if (tone.equals("neutral")) return this;
        return new TradeReputation(Math.max(-5, Math.min(5, score + (tone.equals("polite") ? 1 : -2))), now, lastHit);
    }
}
