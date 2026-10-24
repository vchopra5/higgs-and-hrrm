package dev.higgs;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Non-interactive HUD: all quotes and outcomes come from the game server. */
final class TradeTracker {
    private final TradeTrackerState state = new TradeTrackerState();

    void update(JsonObject event) {
        long now = System.currentTimeMillis();
        TradeTrackerState.Quote next = null;
        if (event.has("costA")) {
            String payment = item(event.getAsJsonObject("costA"));
            JsonObject second = event.getAsJsonObject("costB");
            if (second.get("count").getAsInt() > 0) payment += " + " + item(second);
            next = new TradeTrackerState.Quote(event.has("proposalId") ? event.get("proposalId").getAsString() : "preview", payment,
                item(event.getAsJsonObject("receive")), now + (event.has("expiresInMs") ? Math.max(0, Math.min(120_000, event.get("expiresInMs").getAsLong())) : 15000));
        }
        state.update(clean(event.get("villager").getAsString()), event.get("status").getAsString(), next, now);
    }

    private String item(JsonObject item) { return item.get("count").getAsInt() + " × " + clean(item.get("name").getAsString()); }
    private String clean(String text) { return text.replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " "); }
    void clear() { state.clear(); }

    void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui || client.screen != null) return;
        long now = System.currentTimeMillis();
        float opacity = state.opacity(now);
        if (opacity <= 0) return;
        int alpha = Math.max(5, (int) (255 * opacity));
        int width = Math.min(210, graphics.guiWidth() - 24);
        int x = graphics.guiWidth() - width - 10, y = 68;
        int column = (width - 36) / 2;
        var pay = client.font.split(Component.literal(state.quote == null ? "—" : state.quote.payment()), column);
        var receive = client.font.split(Component.literal(state.quote == null ? "—" : state.quote.received()), column);
        int rows = Math.max(pay.size(), receive.size());
        boolean old = !state.previous.isEmpty() && now - state.updated < 4000;
        int height = 74 + rows * 10 + (old ? 14 : 0);
        int tint = state.active ? 0xA5D8FF : state.status.equals("purchased") ? 0xA6E9BA : 0xFFCF70;
        graphics.fill(x, y, x + width, y + height, ((alpha * 2 / 3) << 24) | 0x101B20);
        graphics.fill(x, y, x + 2, y + height, (alpha << 24) | tint);
        String heading = state.status.equals("interest") ? "INTERESTED?"
            : state.active ? "CONFIRM TRADE?"
            : state.status.equals("purchased") ? "TRADE COMPLETE" : "NO ACTIVE OFFER";
        graphics.text(client.font, heading, x + 12, y + 8, (alpha << 24) | tint, false);
        graphics.text(client.font, client.font.plainSubstrByWidth(state.villager, width - 24), x + 12, y + 21,
            (alpha << 24) | 0xABB9C5, false);
        int top = y + 36;
        if (old) {
            int oldAlpha = (int) (alpha * Math.min(1, (4000 - (now - state.updated)) / 1500f));
            String text = client.font.plainSubstrByWidth(state.previous, width - 24);
            graphics.text(client.font, text, x + 12, top, (Math.max(5, oldAlpha) << 24) | 0x8795A1, false);
            graphics.fill(x + 12, top + 4, x + 12 + client.font.width(text), top + 5, (Math.max(5, oldAlpha) << 24) | 0x8795A1);
            top += 14;
        }
        graphics.text(client.font, "YOU GIVE", x + 12, top, (alpha << 24) | 0xABB9C5, false);
        graphics.text(client.font, "YOU GET", x + 24 + column, top, (alpha << 24) | 0xABB9C5, false);
        for (int i = 0; i < pay.size(); i++) graphics.text(client.font, pay.get(i), x + 12, top + 13 + i * 10, (alpha << 24) | 0xF2F5F4, false);
        for (int i = 0; i < receive.size(); i++) graphics.text(client.font, receive.get(i), x + 24 + column, top + 13 + i * 10, (alpha << 24) | 0xF2F5F4, false);
        String footer = state.active ? "Say yes to confirm" : switch (state.status) {
            case "interest" -> "Just browsing · no purchase";
            case "purchased" -> "Items exchanged successfully";
            case "cancelled" -> "Cancelled · nothing exchanged";
            case "expired" -> "Expired · ask for a fresh quote";
            case "missing_items" -> "You need more payment items";
            case "inventory_full" -> "Make room in your inventory";
            case "changed" -> "Price changed · ask for a new quote";
            default -> "Unavailable · ask for a new quote";
        };
        graphics.fill(x + 12, y + height - 23, x + width - 12, y + height - 22, ((alpha / 3) << 24) | 0xABB9C5);
        graphics.text(client.font, client.font.plainSubstrByWidth(footer, width - 24), x + 12, y + height - 15,
            (alpha << 24) | tint, false);
    }
}
