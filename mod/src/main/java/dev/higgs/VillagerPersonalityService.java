package dev.higgs;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Game-thread adapter. Profiles must reach disk before they are used in a conversation. */
public final class VillagerPersonalityService {
    private static final Logger LOG = LoggerFactory.getLogger("HiggsPersonalities");
    private final PersonalityCatalogue catalogue = loadCatalogue();
    private static PersonalityCatalogue loadCatalogue() {
        try { return PersonalityCatalogue.bundled(); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot load personality catalogue", failure); }
    }
    private VillagerPersonalityStore store;
    public record Prepared(VillagerPersonalityStore.Profile profile, String previousName) {}

    public void start(MinecraftServer server) {
        try {
            store = new VillagerPersonalityStore(server.getWorldPath(LevelResource.ROOT)
                .resolve("data/higgs_hrrm_personalities.json"), catalogue);
        } catch (Exception failure) {
            LOG.error("Personalities unavailable; original save preserved. Check world/data/higgs_hrrm_personalities.json.");
        }
    }
    public void stop() {
        if (store == null) return;
        try { store.close(); }
        catch (Exception failure) { LOG.error("Could not flush villager personalities; check world save permissions."); }
        finally { store = null; }
    }
    private VillagerPersonalityStore.Profile assign(Villager villager) {
        String explicit = null;
        for (var archetype : catalogue.current().archetypes()) {
            String id = archetype.id();
            if (!villager.entityTags().contains("higgs_" + id)) continue;
            if (explicit != null) throw new IllegalStateException("Use only one HIGGS cast tag per villager");
            explicit = id;
        }
        return store.assign(villager.getUUID(), explicit);
    }
    public CompletableFuture<Prepared> prepare(Villager villager) {
        if (store == null) return CompletableFuture.completedFuture(new Prepared(null, null));
        try {
            var previous = store.profile(villager.getUUID());
            var profile = assign(villager);
            return store.flush().thenApply(ignored -> new Prepared(profile, previous == null ? null : previous.name()));
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    public void applyName(Villager villager, Prepared prepared) {
        if (prepared.profile == null) return;
        if (!villager.hasCustomName() || villager.getName().getString().equals(prepared.previousName)) {
            villager.setCustomName(Component.literal(prepared.profile.name()));
        }
    }
    public JsonObject context(VillagerPersonalityStore.Profile profile, ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("catalogueVersion", profile.catalogueVersion());
        result.addProperty("archetypeId", profile.archetypeId());
        result.addProperty("styleId", profile.styleId());
        result.addProperty("familiarity", store.familiarity(profile.villagerId(), player.getUUID()));
        return result;
    }
    public CompletableFuture<JsonObject> confirmVisit(VillagerPersonalityStore.Profile profile, ServerPlayer player) {
        if (store == null || profile == null) return CompletableFuture.completedFuture(VillagerMemoryService.error("No saved identity."));
        try {
            store.confirmVisit(profile.villagerId(), player.getUUID(), System.currentTimeMillis());
            return store.flush().thenApply(ignored -> { JsonObject result = new JsonObject(); result.addProperty("status", "saved"); return result; });
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    public void flush() {
        if (store != null) store.flush().exceptionally(failure -> {
            LOG.error("Could not save villager identities; unsaved profiles will not gossip."); return null;
        });
    }
    /** Lazy identity assignment never opens an upstream session; gossip waits for the save. */
    public VillagerGossip.Policy policy(Villager villager) {
        if (store == null) return VillagerGossip.Policy.neutral();
        try {
            var profile = store.profile(villager.getUUID());
            if (profile == null) profile = assign(villager);
            if (!store.ready(profile)) return null;
            applyName(villager, new Prepared(profile, profile.name()));
            var archetype = catalogue.archetype(profile.catalogueVersion(), profile.archetypeId());
            return new VillagerGossip.Policy(archetype.gossipThreshold(), archetype.gossipDelayMs(), archetype.cooldownPercent(), archetype.interests());
        } catch (RuntimeException failure) { return null; }
    }
}
