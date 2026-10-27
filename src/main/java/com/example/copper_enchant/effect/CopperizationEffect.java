package com.example.copper_enchant.effect;

import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import com.example.copper_enchant.registry.ModEffects;
import com.example.copper_enchant.util.StatueAnimationExtra;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CopperizationEffect extends MobEffect {

    private static final Map<UUID, List<BlockPos>> lightSources = new HashMap<>();
    private static final Map<UUID, Integer> tickProgress = new HashMap<>();
    private static final Map<UUID, List<CopperStatueBlockEntity.StatueAnimationSnapshot>> animationHistory = new HashMap<>();
    private static final Set<UUID> converting = new HashSet<>();
    private static final Set<UUID> attackHalfActive = new HashSet<>();
    private static final Map<UUID, StatueSpiralFx> statueSpirals = new HashMap<>();

    private record StatueSpiralFx(ServerLevel level, double x, double y, double z,
                                  double width, double height, int remainingTicks) {
    }

    private static final Identifier ATTACK_HALF_MODIFIER_ID =
        Identifier.fromNamespaceAndPath("copper_enchant", "attack_half_stage");
    private static final double ATTACK_HALF_AMOUNT = -0.5;

    private static final int TOTAL_TICKS = 100;
    private static final int SEPARATOR_TICKS = 60;
    private static final int STATUE_SPIRAL_TICKS = 40;

    public CopperizationEffect() {
        super(MobEffectCategory.HARMFUL, 0xCC8400);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        UUID uuid = entity.getUUID();
        tickProgress.put(uuid, 0);
        lightSources.put(uuid, new ArrayList<>());
        List<CopperStatueBlockEntity.StatueAnimationSnapshot> history = new ArrayList<>();
        history.add(snapshotOf(entity));
        animationHistory.put(uuid, history);
    }

    private static CopperStatueBlockEntity.StatueAnimationSnapshot snapshotOf(LivingEntity entity) {
        return new CopperStatueBlockEntity.StatueAnimationSnapshot(
            entity.walkAnimation.position(), entity.walkAnimation.speed(), entity.getPose(),
            entity.getXRot(), entity.yBodyRot, entity.getYHeadRot(), entity.tickCount,
            StatueAnimationExtra.capture(entity));
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        UUID uuid = entity.getUUID();
        if (converting.contains(uuid)) return false;

        MobEffectInstance instance = entity.getEffect(ModEffects.COPPERIZATION);
        if (instance == null) return false;

        int remaining = instance.getDuration();
        int elapsed = TOTAL_TICKS - remaining;
        if (elapsed < 0) elapsed = 0;
        if (elapsed > TOTAL_TICKS) elapsed = TOTAL_TICKS;

        tickProgress.put(uuid, elapsed);

        int lightLevel = (int) (14.0 * (elapsed / (double) SEPARATOR_TICKS));
        if (lightLevel < 0) lightLevel = 0;
        if (lightLevel > 14) lightLevel = 14;

        updateLightSources(level, entity, lightLevel);

        boolean shouldHalveAttack = elapsed >= 20 && elapsed < 40;
        if (shouldHalveAttack) {
            if (attackHalfActive.add(uuid)) {
                applyAttackHalfModifier(entity);
            }
        } else if (attackHalfActive.remove(uuid)) {
            removeAttackHalfModifier(entity);
        }

        if (elapsed < 20) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.5, 1, 0.5));
        } else if (elapsed >= 20 && elapsed < 40) {
            entity.setDeltaMovement(Vec3.ZERO);
        } else if (elapsed >= 40 && elapsed < SEPARATOR_TICKS) {
            entity.setDeltaMovement(Vec3.ZERO);
        }

        if (elapsed < SEPARATOR_TICKS) {
            spawnFloodSparks(level, entity, elapsed);
        }

        List<CopperStatueBlockEntity.StatueAnimationSnapshot> history = animationHistory.get(uuid);
        if (history != null && elapsed < TOTAL_TICKS) {
            history.add(snapshotOf(entity));
        }

        if (elapsed >= SEPARATOR_TICKS) {
            converting.add(uuid);
            convertToStatue(level, entity);
            return false;
        }

        return true;
    }

    private static void spawnFloodSparks(ServerLevel level, LivingEntity entity, int elapsed) {
        double progress = Math.min(elapsed, SEPARATOR_TICKS) / (double) SEPARATOR_TICKS;
        double particleY = entity.getY() + entity.getBbHeight() * progress;
        Vec3 entityPos = entity.position();
        RandomSource sparkRandom = entity.getRandom();

        double bodyWidth = entity.getBbWidth();
        double reach = Math.max(0.9, bodyWidth * (1.3 + progress * 1.1) + entity.getBbHeight() * 0.2);
        int count = 5 + Math.min(elapsed, SEPARATOR_TICKS) / 5;

        for (int i = 0; i < count; i++) {
            double theta = sparkRandom.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(sparkRandom.nextDouble()) * reach;
            double offsetX = Math.cos(theta) * distance;
            double offsetZ = Math.sin(theta) * distance;
            double outSpeed = 0.02 + sparkRandom.nextDouble() * 0.04;
            double velX = Math.cos(theta) * outSpeed;
            double velZ = Math.sin(theta) * outSpeed;
            double velY = 0.03 + sparkRandom.nextDouble() * 0.03;
            level.sendParticles(ParticleTypes.WAX_ON,
                entityPos.x + offsetX, particleY, entityPos.z + offsetZ, 1,
                velX, velY, velZ, 0.0);
        }
    }

    private static void scheduleStatueSpiral(ServerLevel level, LivingEntity entity, BlockPos pos) {
        statueSpirals.put(UUID.randomUUID(), new StatueSpiralFx(
            level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            entity.getBbWidth(), entity.getBbHeight(), STATUE_SPIRAL_TICKS));
    }

    private static void tickStatueSpirals(MinecraftServer server) {
        if (statueSpirals.isEmpty()) return;
        Iterator<Map.Entry<UUID, StatueSpiralFx>> iterator = statueSpirals.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, StatueSpiralFx> entry = iterator.next();
            StatueSpiralFx fx = entry.getValue();
            if (fx.remainingTicks() <= 0) {
                iterator.remove();
                continue;
            }
            int age = STATUE_SPIRAL_TICKS - fx.remainingTicks();
            spawnSpiralAround(server, fx, age);
            statueSpirals.put(entry.getKey(), new StatueSpiralFx(
                fx.level(), fx.x(), fx.y(), fx.z(), fx.width(), fx.height(), fx.remainingTicks() - 1));
        }
    }

    private static void spawnSpiralAround(MinecraftServer server, StatueSpiralFx fx, int age) {
        ServerLevel level = server.getLevel(fx.level().dimension());
        if (level == null || !level.isLoaded(BlockPos.containing(fx.x(), fx.y(), fx.z()))) return;

        RandomSource sparkRandom = level.getRandom();
        double heightProgress = Math.min(age, STATUE_SPIRAL_TICKS) / (double) STATUE_SPIRAL_TICKS;
        double particleY = fx.y() + fx.height() * heightProgress;

        int[] rainbow = { 0xFF4040, 0xFFA040, 0xFFFF40, 0x40FF70, 0x40E0FF, 0x6080FF, 0xD060FF, 0xFF60C0 };
        double radius = Math.max(0.7, fx.width() * 0.9 + fx.height() * 0.25);
        int arms = 12;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < arms; i++) {
                double angle = age * 0.5 + (i * 2.0 + pass) * (Math.PI / arms);
                double radiusJitter = radius * (0.9 + sparkRandom.nextDouble() * 0.3);
                double particleX = fx.x() + Math.cos(angle) * radiusJitter;
                double particleZ = fx.z() + Math.sin(angle) * radiusJitter;

                int color = rainbow[(age * 2 + i * 2 + pass) % rainbow.length];
                if (sparkRandom.nextFloat() < 0.12f) {
                    color = 0xFFE8C0;
                }
                float scale = 0.55f + sparkRandom.nextFloat() * 0.25f;
                double velX = (sparkRandom.nextDouble() - 0.5) * 0.015;
                double velZ = (sparkRandom.nextDouble() - 0.5) * 0.015;
                level.sendParticles(new DustParticleOptions(color, scale),
                    particleX, particleY, particleZ, 1,
                    velX, 0.015, velZ, 0.0);
            }
        }
    }

    private static void applyAttackHalfModifier(LivingEntity entity) {
        var attribute = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attribute == null || attribute.hasModifier(ATTACK_HALF_MODIFIER_ID)) return;
        attribute.addPermanentModifier(new AttributeModifier(
            ATTACK_HALF_MODIFIER_ID, ATTACK_HALF_AMOUNT, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void removeAttackHalfModifier(LivingEntity entity) {
        var attribute = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attribute != null) {
            attribute.removeModifier(ATTACK_HALF_MODIFIER_ID);
        }
    }

    @Override
    public void onMobRemoved(ServerLevel level, LivingEntity entity, int amplifier, RemovalReason reason) {
        cleanupLightSources(level, entity.getUUID());
        attackHalfActive.remove(entity.getUUID());
        removeAttackHalfModifier(entity);
    }

    public static int getTickProgress(LivingEntity entity) {
        return tickProgress.getOrDefault(entity.getUUID(), -1);
    }

    public static boolean isCopperizing(LivingEntity entity) {
        return tickProgress.containsKey(entity.getUUID());
    }

    public static void cleanupExpired(MinecraftServer server) {
        tickStatueSpirals(server);

        Iterator<Map.Entry<UUID, List<BlockPos>>> it = lightSources.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<BlockPos>> entry = it.next();
            UUID uuid = entry.getKey();
            Entity entity = server.overworld().getEntityInAnyDimension(uuid);
            if (entity instanceof LivingEntity living) {
                MobEffectInstance effect = living.getEffect(ModEffects.COPPERIZATION);
                if (effect == null) {
                    attackHalfActive.remove(uuid);
                    removeAttackHalfModifier(living);
                    if (living.level() instanceof ServerLevel sl) {
                        cleanupLightSources(sl, uuid);
                    }
                    animationHistory.remove(uuid);
                    tickProgress.remove(uuid);
                    it.remove();
                }
            } else {
                attackHalfActive.remove(uuid);
                animationHistory.remove(uuid);
                tickProgress.remove(uuid);
                it.remove();
            }
        }
    }

    private static void updateLightSources(ServerLevel level, LivingEntity entity, int lightLevel) {
        UUID uuid = entity.getUUID();
        List<BlockPos> oldPositions = lightSources.get(uuid);
        if (oldPositions == null) {
            oldPositions = new ArrayList<>();
            lightSources.put(uuid, oldPositions);
        }

        for (BlockPos pos : oldPositions) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == Blocks.LIGHT) {
                level.removeBlock(pos, false);
            }
        }
        oldPositions.clear();

        List<BlockPos> newPositions = calculateLightPositions(entity);
        for (BlockPos pos : newPositions) {
            if (!level.isLoaded(pos)) continue;
            BlockState lightState = Blocks.LIGHT.defaultBlockState()
                .setValue(LightBlock.LEVEL, lightLevel);
            level.setBlock(pos, lightState, 3);
            oldPositions.add(pos.immutable());
        }
    }

    private static List<BlockPos> calculateLightPositions(LivingEntity entity) {
        List<BlockPos> positions = new ArrayList<>();

        if (entity instanceof EnderDragon dragon) {
            EnderDragonPart[] parts = dragon.getSubEntities();
            for (EnderDragonPart part : parts) {
                AABB box = part.getBoundingBox();
                double cx = (box.minX + box.maxX) / 2.0;
                double cy = (box.minY + box.maxY) / 2.0;
                double cz = (box.minZ + box.maxZ) / 2.0;
                positions.add(BlockPos.containing(cx, cy, cz));
            }
        } else {
            positions.add(entity.blockPosition());
        }

        return positions;
    }

    private static void cleanupLightSources(ServerLevel level, UUID uuid) {
        List<BlockPos> positions = lightSources.get(uuid);
        if (positions != null) {
            for (BlockPos pos : positions) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() == Blocks.LIGHT) {
                    level.removeBlock(pos, false);
                }
            }
            positions.clear();
        }
        lightSources.remove(uuid);
        animationHistory.remove(uuid);
        tickProgress.remove(uuid);
        converting.remove(uuid);
    }

    private void convertToStatue(ServerLevel level, LivingEntity entity) {
        UUID uuid = entity.getUUID();
        BlockPos pos = entity.blockPosition();

        BlockState targetState = level.getBlockState(pos);
        if (!targetState.isAir() && !targetState.canBeReplaced()) {
            entity.removeEffect(ModEffects.COPPERIZATION);
            cleanupLightSources(level, uuid);
            converting.remove(uuid);
            return;
        }

        entity.removeEffect(ModEffects.COPPERIZATION);
        cleanupLightSources(level, uuid);

        scheduleStatueSpiral(level, entity, pos);

        List<CopperStatueBlockEntity.StatueAnimationSnapshot> history = animationHistory.remove(uuid);
        CopperStatueBlockEntity.StatueAnimationSnapshot snapshot =
            (history == null || history.isEmpty()) ? null : history.get(history.size() - 1);

        if (entity instanceof ServerPlayer player) {
            CopperStatueBlockEntity.createAndPlace(level, pos, player, snapshot, history);
            player.kill(level);
        } else {
            CopperStatueBlockEntity.createAndPlace(level, pos, entity, snapshot, history);
            entity.remove(RemovalReason.DISCARDED);
        }

        level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 1.0f,
            (float)(0.9 + entity.getRandom().nextDouble() * 0.2));

        RandomSource random = entity.getRandom();
        Vec3 center = entity.position().add(0, entity.getBbHeight() / 2.0, 0);
        for (int i = 0; i < 22; i++) {
            double dx = (random.nextDouble() - 0.5) * 0.4;
            double dy = (random.nextDouble() - 0.5) * 0.4;
            double dz = (random.nextDouble() - 0.5) * 0.4;
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState()),
                center.x, center.y, center.z, 1,
                dx, dy, dz, 0.1);
        }

        converting.remove(uuid);
    }
}
