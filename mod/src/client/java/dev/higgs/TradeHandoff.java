package dev.higgs;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Cosmetic client-only items. Never spawns a server item or changes inventory. */
final class TradeHandoff {
    private record Flight(ItemEntity item, boolean payment, int delay) {}
    private final List<Flight> flights = new ArrayList<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private ClientLevel level;
    private Entity merchant;
    private int nextId = -2_000_000;
    private long started;
    private boolean chimed;

    void start(Minecraft client, JsonObject event) {
        if (client.level == null || client.player == null) return;
        String id = event.get("tradeId").getAsString();
        if (!seen.add(id)) return;
        if (seen.size() > 64) seen.remove(seen.iterator().next());
        removeItems();
        level = client.level;
        merchant = level.getEntity(event.get("entityId").getAsInt());
        started = System.currentTimeMillis();
        chimed = false;
        var a = event.getAsJsonObject("costA");
        var b = event.getAsJsonObject("costB");
        var received = event.getAsJsonObject("receive");
        if (merchant != null) {
            spawn(client, a, true, 0);
            spawn(client, b, true, 120);
            spawn(client, received, false, 850);
        }
    }

    private Vec3 hand(Minecraft client) {
        return client.player.getEyePosition().add(client.player.getLookAngle().scale(0.85)).add(0, -0.45, 0);
    }

    private Vec3 merchantHand() { return merchant.position().add(0, merchant.getBbHeight() * 0.6, 0); }

    private void spawn(Minecraft client, JsonObject data, boolean payment, int delay) {
        if (data.get("count").getAsInt() <= 0) return;
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(data.get("item").getAsString()));
        if (item == null) return;
        Vec3 start = payment ? hand(client) : merchantHand();
        ItemEntity ghost = new ItemEntity(level, start.x, start.y, start.z, new ItemStack(item), 0, 0, 0);
        ghost.setId(nextId--);
        ghost.setNeverPickUp();
        ghost.setNoGravity(true);
        ghost.noPhysics = true;
        ghost.setInvisible(true);
        level.addEntity(ghost);
        flights.add(new Flight(ghost, payment, delay));
    }

    void tick(Minecraft client) {
        if (level == null) return;
        if (client.level != level || client.player == null) { clear(); return; }
        long age = System.currentTimeMillis() - started;
        if (merchant == null || merchant.isRemoved() || client.player.distanceToSqr(merchant) > 256) removeItems();
        for (var flight : flights) {
            ItemEntity item = flight.item();
            double progress = (age - flight.delay()) / 750.0;
            if (progress >= 1) { level.removeEntity(item.getId(), Entity.RemovalReason.DISCARDED); continue; }
            if (progress < 0) continue;
            item.setInvisible(false);
            double t = progress * progress * (3 - 2 * progress);
            Vec3 from = flight.payment() ? hand(client) : merchantHand();
            Vec3 to = flight.payment() ? merchantHand() : hand(client);
            Vec3 point = from.lerp(to, t).add(0, Math.sin(progress * Math.PI) * 0.22, 0);
            item.setOldPosAndRot();
            item.setPos(point);
            item.setDeltaMovement(Vec3.ZERO);
        }
        if (age >= 1600 && !chimed) {
            chimed = true;
            level.playLocalSound(client.player, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.35f, 1.3f);
        }
        if (age >= 6000) { removeItems(); level = null; }
    }

    private void removeItems() {
        if (level != null) for (var flight : flights) level.removeEntity(flight.item().getId(), Entity.RemovalReason.DISCARDED);
        flights.clear();
    }

    void clear() { removeItems(); level = null; merchant = null; seen.clear(); }
}
