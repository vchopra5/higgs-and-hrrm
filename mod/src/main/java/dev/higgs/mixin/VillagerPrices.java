package dev.higgs.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Villager.class)
public interface VillagerPrices {
    @Invoker("updateSpecialPrices")
    void higgs$updateSpecialPrices(Player player);
}
