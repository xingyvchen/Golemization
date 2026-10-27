package com.example.copper_enchant.client.mixin;

import com.example.copper_enchant.client.util.StatuePoseCapture;
import com.example.copper_enchant.registry.ModEnchantments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/** 客户端攻击入口:手持傀儡化附魔剑击中生物时,开始/续期记录该生物的骨骼帧。 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void copperEnchant$onAttack(Player player, Entity target, CallbackInfo ci) {
        if (!(target instanceof LivingEntity living) || living == player) return;
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (!hasCopperizationEnchantment(player.getMainHandItem(), level)) return;
        StatuePoseCapture.markRecording(living);
    }

    private static boolean hasCopperizationEnchantment(ItemStack stack, Level level) {
        if (stack.isEmpty()) return false;
        RegistryAccess registryAccess = level.registryAccess();
        Optional<Holder.Reference<Enchantment>> enchantment = registryAccess
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(ModEnchantments.COPPERIZATION);
        if (enchantment.isEmpty()) return false;
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        return enchants.getLevel(enchantment.get()) > 0;
    }
}
