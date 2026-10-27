package com.example.copper_enchant.client.util;

import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.CreeperRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.entity.state.RavagerRenderState;
import net.minecraft.client.renderer.entity.state.SkeletonRenderState;
import net.minecraft.client.renderer.entity.state.WardenRenderState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Pose;

public final class StatueAnimationExtraClient {

    private StatueAnimationExtraClient() {
    }

    public static void apply(LivingEntityRenderState state, CompoundTag extra, Pose pose) {
        if (extra == null || extra.isEmpty()) return;

        // ---- 通用动画状态 ----
        // 手臂挥动：对任何有人形手臂的模型生效
        if (state instanceof ArmedEntityRenderState armed) {
            armed.attackTime = extra.getFloatOr("attack", armed.attackTime);
        }
        // 游泳/下蹲/滑翔姿态、体型：仅对"人形/玩家"渲染状态生效
        if (state instanceof HumanoidRenderState humanoid) {
            humanoid.swimAmount = extra.getFloatOr("swim", humanoid.swimAmount);
            humanoid.isFallFlying = extra.getBooleanOr("flying", humanoid.isFallFlying);
            humanoid.isCrouching = pose == Pose.CROUCHING;
            humanoid.isVisuallySwimming = pose == Pose.SWIMMING;
            // 使用物品：拉弓/蓄弩/进食/盾牌格挡等完整手部与身体动作
            humanoid.isUsingItem = extra.getBooleanOr("using_item", humanoid.isUsingItem);
            if (humanoid.isUsingItem) {
                humanoid.ticksUsingItem = extra.getFloatOr("use_ticks", humanoid.ticksUsingItem);
            }
        }
        // 对所有生物生效的通用字段
        state.deathTime = extra.getIntOr("death", (int) state.deathTime);
        state.isBaby = extra.getBooleanOr("baby", state.isBaby);
        state.scale = extra.getFloatOr("scale", state.scale);

        // ---- 生物特化动画 ----
        if (extra.contains("warden_tendril")) {
            if (state instanceof WardenRenderState wardenState) {
                int age = Math.round(state.ageInTicks);
                wardenState.tendrilAnimation = extra.getFloatOr("warden_tendril", wardenState.tendrilAnimation);
                wardenState.heartAnimation = extra.getFloatOr("warden_heart", wardenState.heartAnimation);
                startWarden(wardenState.roarAnimationState, extra, "warden_roar", age);
                startWarden(wardenState.sniffAnimationState, extra, "warden_sniff", age);
                startWarden(wardenState.emergeAnimationState, extra, "warden_emerge", age);
                startWarden(wardenState.diggingAnimationState, extra, "warden_digging", age);
                startWarden(wardenState.attackAnimationState, extra, "warden_attack", age);
                startWarden(wardenState.sonicBoomAnimationState, extra, "warden_sonic", age);
            }
        } else if (extra.contains("skel_aggro")) {
            if (state instanceof SkeletonRenderState skeletonState) {
                skeletonState.isAggressive = extra.getBooleanOr("skel_aggro", skeletonState.isAggressive);
                skeletonState.isShaking = extra.getBooleanOr("skel_shake", skeletonState.isShaking);
            }
        } else if (extra.contains("ravager_attack")) {
            if (state instanceof RavagerRenderState ravagerState) {
                ravagerState.attackTicksRemaining = extra.getIntOr("ravager_attack", 0);
                ravagerState.stunnedTicksRemaining = extra.getIntOr("ravager_stun", 0);
                int roar = extra.getIntOr("ravager_roar", 0);
                ravagerState.roarAnimation = roar > 0 ? 20 - roar : 0;
            }
        } else if (extra.contains("creeper_swell")) {
            if (state instanceof CreeperRenderState creeperState) {
                creeperState.swelling = extra.getFloatOr("creeper_swell", -1.0f);
                creeperState.isPowered = extra.getBooleanOr("creeper_powered", false);
            }
        } else if (extra.contains("phantom_flap")) {
            if (state instanceof PhantomRenderState phantomState) {
                // 原版 PhantomRenderer.extract: flapTime = 独特flap偏移 + ageInTicks。
                // 只写偏移会冻结翅膀相位,必须叠加年龄使 setupAnim 烘焙路径也能继续扇动。
                phantomState.flapTime = extra.getIntOr("phantom_flap", 0) + Math.round(state.ageInTicks);
                phantomState.size = extra.getIntOr("phantom_size", 1);
            }
        }
    }

    private static void startWarden(AnimationState animationState, CompoundTag extra, String key, int age) {
        if (extra.getBooleanOr(key, false)) {
            animationState.start(age);
            long timeMs = extra.getLongOr(key + "_time", 0L);
            if (timeMs > 0) {
                // 从捕获时的相位推进(约50ms/格),精确定位咆哮/嗅探等动作的关键帧
                animationState.fastForward(Math.round(timeMs / 50.0f), 1.0f);
            }
        } else {
            animationState.stop();
        }
    }
}