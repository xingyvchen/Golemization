package com.example.copper_enchant.mixin;

import com.example.copper_enchant.effect.CopperizationEffect;
import com.example.copper_enchant.registry.ModEffects;
import com.example.copper_enchant.registry.ModEnchantments;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Shadow
    public float yHeadRot;

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void copperEnchant$onHurtServer(ServerLevel serverLevel, DamageSource source, float amount,
                                             CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.hasEffect(ModEffects.COPPERIZATION) && source.getEntity() instanceof Player player) {
            ItemStack weapon = player.getMainHandItem();
            if (weapon.is(Items.COPPER_SWORD) && hasCopperizationEnchantment(weapon, serverLevel)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void copperEnchant$onTravel(Vec3 travelVector, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        MobEffectInstance effect = self.getEffect(ModEffects.COPPERIZATION);
        if (effect != null) {
            int remaining = effect.getDuration();
            int elapsed = 100 - remaining;
            if (elapsed >= 20) {
                info.cancel();
            }
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void copperEnchant$onJumpFromGround(CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        MobEffectInstance effect = self.getEffect(ModEffects.COPPERIZATION);
        if (effect != null) {
            int remaining = effect.getDuration();
            int elapsed = 100 - remaining;
            if (elapsed >= 20) {
                info.cancel();
            }
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void copperEnchant$onDoHurtTarget(ServerLevel serverLevel, Entity target,
                                                CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        MobEffectInstance effect = self.getEffect(ModEffects.COPPERIZATION);
        if (effect != null) {
            int remaining = effect.getDuration();
            int elapsed = 100 - remaining;
            if (elapsed >= 40) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "setYHeadRot", at = @At("HEAD"), cancellable = true)
    private void copperEnchant$limitHeadRotation(float yHeadRot, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        MobEffectInstance effect = self.getEffect(ModEffects.COPPERIZATION);
        if (effect != null) {
            int remaining = effect.getDuration();
            int elapsed = 100 - remaining;
            if (elapsed >= 40) {
                float delta = yHeadRot - this.yHeadRot;
                this.yHeadRot = this.yHeadRot + delta * 0.5f;
                info.cancel();
            }
        }
    }

    private boolean hasCopperizationEnchantment(ItemStack stack, ServerLevel level) {
        RegistryAccess registryAccess = level.registryAccess();
        Optional<Holder.Reference<Enchantment>> enchantment = registryAccess
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(ModEnchantments.COPPERIZATION);
        if (enchantment.isEmpty()) return false;
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        return enchants.getLevel(enchantment.get()) > 0;
    }
}
