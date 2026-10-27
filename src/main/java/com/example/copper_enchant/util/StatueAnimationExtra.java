package com.example.copper_enchant.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.warden.Warden;

public final class StatueAnimationExtra {

    private StatueAnimationExtra() {
    }

    public static CompoundTag capture(LivingEntity entity) {
        CompoundTag tag = new CompoundTag();

        // ---- 通用动画状态（作用于所有生物：头部/手臂挥动/腿脚摆动/身体姿态）----
        // 手臂挥动（挥击动画）进度
        tag.putFloat("attack", entity.attackAnim);
        // 游泳过渡动画量
        tag.putFloat("swim", entity.getSwimAmount(1.0f));
        // 死亡动画进度
        tag.putInt("death", entity.deathTime);
        // 鞘翅滑翔姿态
        tag.putBoolean("flying", entity.isFallFlying());
        // 幼年体型（影响整体缩放与部分骨骼比例）
        tag.putBoolean("baby", entity.isBaby());
        // 体型倍率
        tag.putFloat("scale", entity.getScale());
        // 使用物品进度（拉弓/蓄弩/进食/盾牌格挡等带动手部与身体的完整动作）
        tag.putBoolean("using_item", entity.isUsingItem());
        tag.putFloat("use_ticks", entity.getUseItemRemainingTicks());

        // ---- 生物特化动画（按类型补充分段，可继续扩展）----
        if (entity instanceof Warden warden) {
            tag.putFloat("warden_tendril", warden.getTendrilAnimation(1.0f));
            tag.putFloat("warden_heart", warden.getHeartAnimation(1.0f));
            // getTimeInMillis 的第一个实参必须是"当前年龄"(tick),否则相位计算错误
            captureAnimationTime(tag, "warden_roar", warden.roarAnimationState, warden.tickCount);
            captureAnimationTime(tag, "warden_sniff", warden.sniffAnimationState, warden.tickCount);
            captureAnimationTime(tag, "warden_emerge", warden.emergeAnimationState, warden.tickCount);
            captureAnimationTime(tag, "warden_digging", warden.diggingAnimationState, warden.tickCount);
            captureAnimationTime(tag, "warden_attack", warden.attackAnimationState, warden.tickCount);
            captureAnimationTime(tag, "warden_sonic", warden.sonicBoomAnimationState, warden.tickCount);
        } else if (entity instanceof AbstractSkeleton skeleton) {
            tag.putBoolean("skel_aggro", skeleton.isAggressive());
            tag.putBoolean("skel_shake", skeleton.isShaking());
        } else if (entity instanceof Ravager ravager) {
            tag.putInt("ravager_attack", ravager.getAttackTick());
            tag.putInt("ravager_stun", ravager.getStunnedTick());
            tag.putInt("ravager_roar", ravager.getRoarTick());
        } else if (entity instanceof Creeper creeper) {
            tag.putFloat("creeper_swell", creeper.getSwelling(1.0f));
            tag.putBoolean("creeper_powered", creeper.isPowered());
        } else if (entity instanceof Phantom phantom) {
            tag.putInt("phantom_flap", phantom.getUniqueFlapTickOffset());
            tag.putInt("phantom_size", phantom.getPhantomSize());
        } else if (entity instanceof Pufferfish pufferfish) {
            // 河豚的膨胀是靠切换 Big/Mid/Small 三套不同几何模型实现的,并非骨骼缩放。
            // 必须记录其 puff 状态(0=small,1=mid,2=big),否则雕像回放时几何会随机错乱。
            tag.putInt("puffer_puff", pufferfish.getPuffState());
        }
        return tag;
    }

    /** 记录动画状态的启动与否及相位时间(ms)。相位 = 当前年龄 - 启动 tick,乘 50ms。
     *  currentAge 应为该生物当前的 tickCount,与渲染时 KeyframeAnimation.apply 传入的 ageInTicks 对齐。 */
    private static void captureAnimationTime(CompoundTag tag, String key, AnimationState state, int currentAge) {
        tag.putBoolean(key, state.isStarted());
        if (state.isStarted()) {
            tag.putLong(key + "_time", state.getTimeInMillis(currentAge));
        }
    }
}