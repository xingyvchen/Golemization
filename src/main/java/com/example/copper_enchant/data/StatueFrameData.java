package com.example.copper_enchant.data;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * 傀儡逐帧数据:
 * <ul>
 *   <li>{@code rotations} - 捕获时模型<strong>全部骨骼</strong>的最终旋转(rad*1000,short,按 DFS 路径对齐);
 *       (v2) 由捕获侧用完整渲染状态 setupAnim 后直接采集,保证手/脚/腿/头/躯干等所有部位完整保留;</li>
 *   <li>{@code tra} - 全部骨骼的最终位移 x/y/z(*1000),防止复用时基线位移错位/累积下沉;</li>
 *   <li>{@code vis} - 全部骨骼的可见性(1 可见/0 隐藏,如持物收起时手臂隐藏);</li>
 *   <li>{@code extra} - 每帧渲染参数(驱动共享模型图层:盔甲/手持物/游泳/使用物品等,含环境状态)。</li>
 * </ul>
 */
public record StatueFrameData(short[] rotations, short[] tra, byte[] vis, FrameExtra extra) {

    /** 每帧 extra 的紧凑字节数(v2 格式,不含版本头)。 */
    public static final int EXTRA_BYTES = 23;
    /** 旧格式(v1)每帧字节数。 */
    public static final int EXTRA_BYTES_V1 = 14;

    public record FrameExtra(float ageInTicks, float walkPos, float walkSpeed, float swimAmount,
                             float attackTime, int poseOrdinal, byte flags, float scale,
                             int deathTime, int useTicks, float xRot, float bodyRot, float yRot,
                             int rightArmPose, int leftArmPose, int mainArm, int attackArm,
                             CompoundTag typeExtra) {
        public static final int FLAG_IN_WATER = 1;
        public static final int FLAG_USING_ITEM = 2;
        public static final int FLAG_CROUCHING = 4;
        public static final int FLAG_SPIN_ATTACK = 8;
        public static final int FLAG_FALL_FLYING = 16;
        public static final int FLAG_BABY = 32;
        /** 捕获时该生物处于露天白昼(亡灵会燃烧/颤抖的动画状态)。 */
        public static final int FLAG_SUNBURN = 64;
    }

    public static short quantize(float rot) {
        return (short) Math.round(rot * 1000f);
    }

    public static float unquantize(short value) {
        return value / 1000f;
    }

    /**
     * v2 帧参数打包为 23 字节/帧:
     * age(u16) walkPos(s16) walkSpeed(s16) swim(u8) attack(u8) pose(u8) flags(u8) scale(s16)
     * death(u8) use(u8) xRot(s16) bodyRot(s16) yRot(s16) rightArmPose(u8) leftArmPose(u8) arms(u8)。
     */
    public static byte[] packExtras(List<FrameExtra> extras) {
        byte[] out = new byte[extras.size() * EXTRA_BYTES];
        int i = 0;
        for (FrameExtra e : extras) {
            int age = clampU16(Math.round(e.ageInTicks()));
            out[i++] = (byte) ((age >> 8) & 0xFF);
            out[i++] = (byte) (age & 0xFF);
            short walkPos = quantize(e.walkPos());
            out[i++] = (byte) ((walkPos >> 8) & 0xFF);
            out[i++] = (byte) (walkPos & 0xFF);
            short walkSpeed = quantize(e.walkSpeed());
            out[i++] = (byte) ((walkSpeed >> 8) & 0xFF);
            out[i++] = (byte) (walkSpeed & 0xFF);
            out[i++] = (byte) Math.round(clamp01(e.swimAmount()) * 255f);
            out[i++] = (byte) Math.round(clamp01(e.attackTime()) * 255f);
            out[i++] = (byte) (e.poseOrdinal() & 0xFF);
            out[i++] = e.flags();
            short scale = quantize(clampScale(e.scale()));
            out[i++] = (byte) ((scale >> 8) & 0xFF);
            out[i++] = (byte) (scale & 0xFF);
            out[i++] = (byte) clampByte(e.deathTime());
            out[i++] = (byte) clampByte(e.useTicks());
            short xRot = quantize(clampRot(e.xRot()));
            out[i++] = (byte) ((xRot >> 8) & 0xFF);
            out[i++] = (byte) (xRot & 0xFF);
            short bodyRot = quantize(clampRot(e.bodyRot()));
            out[i++] = (byte) ((bodyRot >> 8) & 0xFF);
            out[i++] = (byte) (bodyRot & 0xFF);
            short yRot = quantize(clampRot(e.yRot()));
            out[i++] = (byte) ((yRot >> 8) & 0xFF);
            out[i++] = (byte) (yRot & 0xFF);
            out[i++] = (byte) (e.rightArmPose() & 0xFF);
            out[i++] = (byte) (e.leftArmPose() & 0xFF);
            out[i++] = (byte) (((e.mainArm() & 3) << 2) | (e.attackArm() & 3));
        }
        return out;
    }

    public static List<FrameExtra> unpackExtras(byte[] packed) {
        return unpackExtras(packed, EXTRA_BYTES);
    }

    public static List<FrameExtra> unpackExtras(byte[] packed, int bytesPerFrame) {
        int count = packed.length / bytesPerFrame;
        List<FrameExtra> list = new ArrayList<>(count);
        int i = 0;
        for (int f = 0; f < count; f++) {
            int age = ((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF);
            i += 2;
            short walkPos = (short) (((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF));
            i += 2;
            short walkSpeed = (short) (((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF));
            i += 2;
            float swim = (packed[i] & 0xFF) / 255f;
            i++;
            float attack = (packed[i] & 0xFF) / 255f;
            i++;
            int pose = packed[i] & 0xFF;
            i++;
            byte flags = packed[i];
            i++;
            short scale = (short) (((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF));
            i += 2;
            int death = packed[i] & 0xFF;
            i++;
            int use = packed[i] & 0xFF;
            i++;
            if (bytesPerFrame <= EXTRA_BYTES_V1) {
                list.add(new FrameExtra(age, unquantize(walkPos), unquantize(walkSpeed), swim, attack,
                    pose, flags, unquantize(scale), death, use, 0f, 0f, 0f, 0, 0, 0, 0, null));
                continue;
            }
            short xRot = (short) (((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF));
            i += 2;
            short bodyRot = (short) (((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF));
            i += 2;
            short yRot = (short) (((packed[i] & 0xFF) << 8) | (packed[i + 1] & 0xFF));
            i += 2;
            int rightArmPose = packed[i] & 0xFF;
            i++;
            int leftArmPose = packed[i] & 0xFF;
            i++;
            int arms = packed[i] & 0xFF;
            i++;
            list.add(new FrameExtra(age, unquantize(walkPos), unquantize(walkSpeed), swim, attack,
                pose, flags, unquantize(scale), death, use, unquantize(xRot), unquantize(bodyRot),
                unquantize(yRot), rightArmPose, leftArmPose, (arms >> 2) & 3, arms & 3, null));
        }
        return list;
    }

    private static float clampRot(float v) {
        return Math.max(-32f, Math.min(32f, v));
    }

    private static int clampU16(int v) {
        return Math.max(0, Math.min(65535, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clampScale(float v) {
        return Math.max(-32f, Math.min(32f, v));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
