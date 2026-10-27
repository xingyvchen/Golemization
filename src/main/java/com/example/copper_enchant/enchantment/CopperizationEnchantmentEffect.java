package com.example.copper_enchant.enchantment;

import com.example.copper_enchant.registry.ModEffects;
import com.mojang.serialization.MapCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record CopperizationEnchantmentEffect() implements EnchantmentEntityEffect {

    public static final MapCodec<CopperizationEnchantmentEffect> CODEC =
        MapCodec.unit(new CopperizationEnchantmentEffect());

    private static final int FORCE_AFTER_HITS = 30;
    private static final float LOW_HEALTH_FORCE_RATIO = 0.1f;
    private static final float BOSS_FORCE_HEALTH_RATIO = 0.2f;

    private static final Map<UUID, String> trackedTargetType = new HashMap<>();
    private static final Map<UUID, Integer> hitCountWithoutEffect = new HashMap<>();

    @Override
    public void apply(ServerLevel serverLevel, int level, EnchantedItemInUse context, Entity target, Vec3 pos) {
        LivingEntity livingTarget;
        if (target instanceof EnderDragonPart part) {
            livingTarget = part.parentMob;
        } else if (target instanceof LivingEntity living) {
            livingTarget = living;
        } else {
            return;
        }
        if (livingTarget == null || livingTarget instanceof EnderDragon || livingTarget.hasEffect(ModEffects.COPPERIZATION)) return;

        UUID attackerId = context.owner().getUUID();
        String targetTypeKey = livingTarget.getType().getDescriptionId();

        String lastType = trackedTargetType.get(attackerId);
        if (!targetTypeKey.equals(lastType)) {
            trackedTargetType.put(attackerId, targetTypeKey);
            hitCountWithoutEffect.put(attackerId, 0);
        }
        int hits = hitCountWithoutEffect.getOrDefault(attackerId, 0) + 1;
        hitCountWithoutEffect.put(attackerId, hits);

        // 第一下 100% 不触发,保证客户端已从首次击中开始记录该生物的骨骼帧
        if (hits == 1) return;

        boolean forceApply = false;
        float maxHealth = livingTarget.getMaxHealth();
        float currentHealth = livingTarget.getHealth();
        float healthRatio = maxHealth > 0.0f ? currentHealth / maxHealth : 1.0f;
        if (hits >= FORCE_AFTER_HITS) {
            forceApply = true;
        } else if (healthRatio <= LOW_HEALTH_FORCE_RATIO) {
            forceApply = true;
        } else if (isBoss(livingTarget) && healthRatio <= BOSS_FORCE_HEALTH_RATIO) {
            forceApply = true;
        }

        if (!forceApply) {
            float healthLostPercent = 1.0f - healthRatio;
            int lostTenths = (int) (healthLostPercent * 10);
            float probability = 0.10f + 0.20f * lostTenths;
            if (probability > 0.95f) probability = 0.95f;
            if (probability < 0.10f) probability = 0.10f;
            forceApply = livingTarget.getRandom().nextFloat() < probability;
        }

        if (forceApply) {
            livingTarget.forceAddEffect(new MobEffectInstance(ModEffects.COPPERIZATION, 100, 0, false, true, true),
                context.owner());
            hitCountWithoutEffect.put(attackerId, 0);
        }
    }

    private static boolean isBoss(LivingEntity entity) {
        return entity instanceof EnderDragon || entity instanceof WitherBoss;
    }

    @Override
    public MapCodec<? extends EnchantmentEntityEffect> codec() {
        return CODEC;
    }
}
