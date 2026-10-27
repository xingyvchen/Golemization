package com.example.copper_enchant.client.util;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.client.mixin.ModelPartAccessor;
import com.example.copper_enchant.data.StatueFrameData;
import com.example.copper_enchant.data.StatueFrames;
import com.example.copper_enchant.network.StatueFramesPayload;
import com.example.copper_enchant.registry.ModEffects;
import com.example.copper_enchant.util.StatueAnimationExtra;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端骨骼级姿势捕获与上报。
 * <p>
 * 触发:玩家用傀儡化附魔剑击中生物的第一下起,客户端即开始逐帧记录该生物的
 * 完整渲染参数(实体年龄为时间轴,按游戏 tick 去重,20tps 下 10 秒 = 200 帧),
 * 直到 30 秒无再次击中自动清除;傀儡化生效后该生物的帧转为雕像专用缓存。
 * <p>
 * 上报:转化完成后(客户端看到该生物消失),截取最后 MAX_FRAMES(10 秒)帧,
 * 通过 {@link StatueFramesPayload} 上传服务端,存入雕像方块实体内部。
 * 不满足 10 秒则有多少传多少。
 * <p>
 * 回放:雕像渲染优先使用方块实体内部存储的帧参数,先写入渲染状态再做
 * setupAnim 烘焙到独立克隆模型(腿部/躯干等全部骨骼由此完整还原),
 * 并根据雕像当前环境(水中/日光)做适配。没有存储帧时回退到服务端参数快照。
 */
public final class StatuePoseCapture {

    /** 一帧完整快照:渲染参数(extra) + 逐骨骼数据(rotations/tra/vis,由绘制时刻记录)。 */
    public record CapturedFrame(float age, short[] rotations, short[] tra, byte[] vis,
                                StatueFrameData.FrameExtra extra) {
    }

    private record OverrideEntry(StatueFrames frames, int index) {
    }

    /** 10 秒窗口:20tps 下约 200 帧;捕获不足则动态截取。 */
    private static final int MAX_FRAMES = 200;
    private static final long RECORD_TICKS = 30 * 20L;

    private static final Map<UUID, List<CapturedFrame>> FRAMES = new HashMap<>();
    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();
    private static final Set<UUID> EFFECT_SEEN = new HashSet<>();
    private static final Set<UUID> SENT = new HashSet<>();
    /** 每类生物的骨骼路径表(按模型 DFS 顺序,一张表全帧共用)。 */
    private static final Map<UUID, List<String>> FRAME_PATHS = new HashMap<>();
    /** 每 UUID 最近一次逐骨骼捕获所在 tick,避免同一帧内图层模型覆盖本体骨骼。 */
    private static final Map<UUID, Integer> BONE_TICK = new HashMap<>();
    private static final IdentityHashMap<EntityRenderState, LivingEntity> STATE_TO_ENTITY = new IdentityHashMap<>();
    /** 渲染状态 → 本体骨骼模型部件数(提交阶段登记)。捕获时用部件数判定本体,避免图层模型覆盖本体骨骼。
     *  用数值而非实例 identity:绘制阶段传入的模型引用可能与提交阶段不同,identity 比较会误杀采集导致透明。 */
    private static final IdentityHashMap<Object, Integer> STATE_BASE_PARTS = new IdentityHashMap<>();

    /** 绘制时覆盖注册表:键为渲染状态对象(身体与盔甲节点共用同一个)。 */
    private static final IdentityHashMap<Object, OverrideEntry> DRAW_OVERRIDES = new IdentityHashMap<>();

    private static ClientLevel currentLevel;

    private StatuePoseCapture() {
    }

    /** 由攻击 mixin 调用:被傀儡化附魔剑击中,开始/续期记录该生物。 */
    public static void markRecording(LivingEntity entity) {
        if (entity == null) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        UUID uuid = entity.getUUID();
        if (SENT.contains(uuid)) {
            // 上一轮已上报(如玩家重生后再次被转化):开启新一轮记录
            FRAMES.remove(uuid);
            FRAME_PATHS.remove(uuid);
            BONE_TICK.remove(uuid);
            EFFECT_SEEN.remove(uuid);
            SENT.remove(uuid);
            STATE_TO_ENTITY.entrySet().removeIf(e -> {
                if (e.getValue().getUUID().equals(uuid)) {
                    STATE_BASE_PARTS.remove(e.getKey());
                    return true;
                }
                return false;
            });
            CopperEnchantMod.LOGGER.info("[StatuePoseCapture] 新一轮记录开始 uuid={}", uuid);
        }
        LAST_HIT.put(uuid, level.getGameTime());
        FRAMES.computeIfAbsent(uuid, u -> new ArrayList<>());
    }

    /** 由 LivingEntityRendererMixin 在 extractRenderState 时调用。 */
    public static void onExtract(LivingEntity entity, LivingEntityRenderState state) {
        if (entity == null || state == null) return;
        UUID uuid = entity.getUUID();
        if (!LAST_HIT.containsKey(uuid)) return;
        STATE_TO_ENTITY.put(state, entity);
        if (entity.hasEffect(ModEffects.COPPERIZATION) && !EFFECT_SEEN.contains(uuid)) {
            // 新的一轮傀儡化生效:允许重新上报(保留已记录的攻击期间帧)
            SENT.remove(uuid);
            EFFECT_SEEN.add(uuid);
            CopperEnchantMod.LOGGER.info("[StatuePoseCapture] 傀儡化效果生效,开始效果期 uuid={}", uuid);
        }
    }

    /** 由 LivingEntityRendererMixin 在 submit 返回后调用,记录该帧渲染参数(自首次击中起,直到上报)。 */
    public static void onSubmitted(LivingEntityRenderState state, EntityModel<?> model) {
        if (state == null) return;
        LivingEntity entity = STATE_TO_ENTITY.get(state);
        if (entity == null) return;
        UUID uuid = entity.getUUID();
        if (!LAST_HIT.containsKey(uuid)) return;
        if (SENT.contains(uuid)) return; // 已上报,不再记录

        // 登记本体骨骼模型部件数:捕获阶段以部件数(而非实例 identity)判定本体,
        // 避开盔甲等图层模型,同时兼容绘制阶段模型引用与提交阶段不一致
        STATE_BASE_PARTS.put(state, countParts(model.root()));

        StatueFrameData.FrameExtra extra = captureExtra(state, entity);
        float age = state.ageInTicks;
        int tick = (int) age;
        List<CapturedFrame> frames = FRAMES.computeIfAbsent(uuid, u -> new ArrayList<>());
        if (!frames.isEmpty() && (int) frames.get(frames.size() - 1).age() == tick) {
            // 同一游戏 tick 内的多次渲染只保留最后一次,保证 20tps 下 200 帧 = 10 秒
            frames.set(frames.size() - 1, new CapturedFrame(age, null, null, null, extra));
            return;
        }
        frames.add(new CapturedFrame(age, null, null, null, extra));
        if (frames.size() == 1 || frames.size() % 60 == 0) {
            CopperEnchantMod.LOGGER.info("[StatuePoseCapture] 记录帧 uuid={} 帧数={} age={}", uuid, frames.size(), age);
        }
        while (frames.size() > MAX_FRAMES) {
            frames.remove(0);
        }
    }

    private static StatueFrameData.FrameExtra captureExtra(LivingEntityRenderState state, LivingEntity entity) {
        byte flags = 0;
        if (state.isInWater) flags |= StatueFrameData.FrameExtra.FLAG_IN_WATER;
        if (state.isAutoSpinAttack) flags |= StatueFrameData.FrameExtra.FLAG_SPIN_ATTACK;
        if (state.isBaby) flags |= StatueFrameData.FrameExtra.FLAG_BABY;
        float swim = 0f;
        float attack = 0f;
        int useTicks = 0;
        int rPose = 0;
        int lPose = 0;
        int main = 0;
        int attackArm = 0;
        if (state instanceof HumanoidRenderState h) {
            swim = h.swimAmount;
            if (h.isUsingItem) flags |= StatueFrameData.FrameExtra.FLAG_USING_ITEM;
            if (h.isCrouching) flags |= StatueFrameData.FrameExtra.FLAG_CROUCHING;
            if (h.isFallFlying) flags |= StatueFrameData.FrameExtra.FLAG_FALL_FLYING;
            useTicks = Math.round(h.ticksUsingItem);
        }
        if (state instanceof ArmedEntityRenderState a) {
            attack = a.attackTime;
            if (a.rightArmPose != null) rPose = a.rightArmPose.ordinal();
            if (a.leftArmPose != null) lPose = a.leftArmPose.ordinal();
            if (a.mainArm != null) main = a.mainArm.ordinal();
            if (a.attackArm != null) attackArm = a.attackArm.ordinal();
        }
        int poseOrdinal = state.pose != null ? state.pose.ordinal() : Pose.STANDING.ordinal();
        CompoundTag typeExtra = null;
        try {
            typeExtra = StatueAnimationExtra.capture(entity);
        } catch (Exception ignored) {
        }
        return new StatueFrameData.FrameExtra(state.ageInTicks, state.walkAnimationPos, state.walkAnimationSpeed,
            swim, attack, poseOrdinal, flags, state.scale, Math.round(state.deathTime), useTicks,
            state.xRot, state.bodyRot, state.yRot, rPose, lPose, main, attackArm, typeExtra);
    }

    /**
     * 绘制阶段逐骨骼快照:由 {@code ModelFeatureRendererMixin} 在 setupAnim 之后调用,
     * 此时共享模型已按当前渲染状态完成全部骨骼计算(手/脚/腿/头/躯干最终旋转/位移/可见性)。
     * 采集结果并入本 tick 已在提交阶段创建的帧快照。仅第一次调用生效,避免图层模型覆盖本体骨骼。
     */
    public static void recordBonePose(Object renderState, Model model) {
        if (!(renderState instanceof LivingEntityRenderState state)) return;
        LivingEntity entity = STATE_TO_ENTITY.get(renderState);
        if (entity == null) return;
        UUID uuid = entity.getUUID();
        if (!LAST_HIT.containsKey(uuid)) return;
        if (SENT.contains(uuid)) return;
        // 只捕获本体骨骼模型:盔甲/手持物等图层模型结构不同(部件数不同),若被当作本体会导致骨骼错位。
        // 用部件数判定而非实例 identity:绘制阶段模型引用可能与提交阶段不同,identity 会误杀采集。
        Integer baseParts = STATE_BASE_PARTS.get(renderState);
        if (baseParts == null || countParts(model.root()) != baseParts) return;
        int tick = (int) state.ageInTicks;
        Integer lastTick = BONE_TICK.get(uuid);
        if (lastTick != null && lastTick == tick) return; // 本 tick 已捕获本体骨骼
        BONE_TICK.put(uuid, tick);

        ModelPart root = model.root();
        List<String> paths = FRAME_PATHS.get(uuid);
        if (paths == null) {
            paths = new ArrayList<>(64);
            collectPaths(root, "", paths);
            FRAME_PATHS.put(uuid, paths);
        }
        // 结构防护:河豚等按状态切换整套几何(不同 puff 模型部件数不同)。若本渲染时模型部件数
        // 与已登记的路径数不一致,说明中途换过几何,按旧 paths 尺寸填新模型骨骼数组会越界崩溃。
        // 直接跳过本次骨骼采集(帧保持 null,由发送端回退兜底),绝不让绘制崩。
        if (countParts(root) != paths.size()) {
            CopperEnchantMod.LOGGER.info("[StatuePoseCapture] 跳过骨骼采集:模型结构变化 uuid={} parts={} paths={}",
                uuid, countParts(root), paths.size());
            return;
        }
        int n = paths.size() * 3;
        short[] rotations = new short[n];
        short[] traArr = new short[n];
        byte[] visArr = new byte[paths.size()];
        fillBoneData(root, rotations, traArr, visArr, 0);

        List<CapturedFrame> frames = FRAMES.get(uuid);
        if (frames == null || frames.isEmpty()) return;
        for (int i = frames.size() - 1; i >= 0; i--) {
            CapturedFrame f = frames.get(i);
            if ((int) f.age() == tick) {
                frames.set(i, new CapturedFrame(f.age(), rotations, traArr, visArr, f.extra()));
                return;
            }
            if ((int) f.age() < tick) return;
        }
    }

    /** 统计模型根根节点的全部部件数(含自身),用作病因识别本体/图层的结构指纹。 */
    private static int countParts(ModelPart root) {
        int n = 0;
        for (ModelPart ignored : root.getAllParts()) n++;
        return n;
    }

    private static void collectPaths(ModelPart part, String path, List<String> out) {
        out.add(path);
        Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).copperEnchant$getChildren();
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            collectPaths(entry.getValue(),
                path.isEmpty() ? entry.getKey() : path + "/" + entry.getKey(), out);
        }
    }

    /** 按 DFS 前序把全部骨骼的旋转/位移/可见性写入定长数组,顺序与 collectPaths 对齐。 */
    private static int fillBoneData(ModelPart part, short[] rotations, short[] tra, byte[] vis, int idx) {
        // 越界防御:任何结构漂移(如模型在捕获中切换)都绝不允许此处数组越界崩渲染。
        if (idx < 0 || idx + 2 >= rotations.length) return idx;
        rotations[idx] = StatueFrameData.quantize(part.xRot);
        rotations[idx + 1] = StatueFrameData.quantize(part.yRot);
        rotations[idx + 2] = StatueFrameData.quantize(part.zRot);
        tra[idx] = StatueFrameData.quantize(part.x);
        tra[idx + 1] = StatueFrameData.quantize(part.y);
        tra[idx + 2] = StatueFrameData.quantize(part.z);
        vis[idx / 3] = part.visible ? (byte) 1 : (byte) 0;
        idx += 3;
        Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).copperEnchant$getChildren();
        for (ModelPart child : children.values()) {
            idx = fillBoneData(child, rotations, tra, vis, idx);
        }
        return idx;
    }

    /** 每客户端 tick 调用:效果结束后上报帧(只一次);30 秒无攻击清理未触发效果的缓存;世界切换时全清。 */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != currentLevel) {
            clearAll();
            currentLevel = level;
            return;
        }
        if (level == null) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = LAST_HIT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID uuid = entry.getKey();
            Entity entity = level.getEntity(uuid);
            boolean hasEffect = entity instanceof LivingEntity living && living.hasEffect(ModEffects.COPPERIZATION);
            if (hasEffect) continue; // 转化中,保留并继续记录
            if (!SENT.contains(uuid) && (EFFECT_SEEN.contains(uuid) || entity == null)) {
                // 效果已结束(生物消失/玩家重生),或从未见过效果但实体已消失(短促转化):
                // 截取最后 10s 帧上报服务端,只上报一次
                SENT.add(uuid);
                CopperEnchantMod.LOGGER.info("[StatuePoseCapture] 效果结束,准备上报帧 uuid={} 帧数={}",
                    uuid, FRAMES.get(uuid) == null ? 0 : FRAMES.get(uuid).size());
                sendFrames(uuid);
                continue;
            }
            if (now - entry.getValue() > RECORD_TICKS) {
                it.remove();
                FRAMES.remove(uuid);
                FRAME_PATHS.remove(uuid);
                BONE_TICK.remove(uuid);
                EFFECT_SEEN.remove(uuid);
                SENT.remove(uuid);
                STATE_TO_ENTITY.entrySet().removeIf(e -> {
                    if (e.getValue().getUUID().equals(uuid)) {
                        STATE_BASE_PARTS.remove(e.getKey());
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private static void sendFrames(UUID uuid) {
        List<CapturedFrame> all = FRAMES.get(uuid);
        if (all == null || all.isEmpty()) {
            CopperEnchantMod.LOGGER.warn("[StatuePoseCapture] 无帧可上报 uuid={}", uuid);
            return;
        }
        int from = Math.max(0, all.size() - MAX_FRAMES);
        List<CapturedFrame> window = all.subList(from, all.size());

        // 若整套帧完全没采到任何有效骨骼数据(如采集被阻断),退化为纯 extra 模式:paths 置空,
        // 让渲染端走 setupAnim 烘焙,绝不把全 0 的 vis 数组回放成隐藏(否则雕像变成透明)。
        boolean anyRealBones = false;
        for (CapturedFrame f : window) {
            short[] r = f.rotations();
            if (r != null && r.length > 0) {
                anyRealBones = true;
                break;
            }
        }
        List<String> paths = FRAME_PATHS.getOrDefault(uuid, List.of());
        if (!anyRealBones) {
            paths = List.of();
        }
        int parts = paths.size();
        List<StatueFrameData.FrameExtra> extras = new ArrayList<>(window.size());
        List<CompoundTag> typeExtras = new ArrayList<>(window.size());
        List<short[]> rotationsList = new ArrayList<>(window.size());
        List<short[]> traList = new ArrayList<>(window.size());
        List<byte[]> visList = new ArrayList<>(window.size());
        // 骨骼缺失的帧(该 tick 未在绘制阶段采到)回退到最近一次完整骨骼,保证每帧数组齐全且等长,
        // 避免写包时 null 元素 NPE、也保证服务端定长打包不错位;完全无骨骼数据时退化为空数组(走 setupAnim 回退)。
        short[] lastRot = null, lastTra = null;
        byte[] lastVis = null;
        for (CapturedFrame frame : window) {
            StatueFrameData.FrameExtra extra = frame.extra();
            extras.add(extra);
            typeExtras.add(extra != null && extra.typeExtra() != null ? extra.typeExtra() : new CompoundTag());
            short[] r = frame.rotations();
            short[] t = frame.tra();
            byte[] v = frame.vis();
            if (r == null && lastRot != null) {
                r = lastRot;
                t = lastTra;
                v = lastVis;
            }
            if (r == null) {
                r = new short[parts * 3];
                t = new short[parts * 3];
                v = new byte[parts];
            }
            rotationsList.add(r);
            traList.add(t);
            visList.add(v);
            if (r.length > 0) {
                lastRot = r;
                lastTra = t;
                lastVis = v;
            }
        }
        byte[] packed = StatueFrameData.packExtras(extras);
        int bonePaths = parts;
        int boneFrames = 0;
        for (short[] r : rotationsList) {
            if (r != null && r.length > 0) boneFrames++;
        }
        CopperEnchantMod.LOGGER.info("[StatuePoseCapture] 发送帧包 uuid={} 帧数={} 部件数={} 字节={}",
            uuid, window.size(), bonePaths, packed.length + (boneFrames * bonePaths * 7));
        ClientPlayNetworking.send(new StatueFramesPayload(
            uuid, paths, rotationsList, traList, visList, packed, typeExtras));
    }

    public static void clearAll() {
        FRAMES.clear();
        FRAME_PATHS.clear();
        BONE_TICK.clear();
        LAST_HIT.clear();
        EFFECT_SEEN.clear();
        SENT.clear();
        STATE_TO_ENTITY.clear();
        STATE_BASE_PARTS.clear();
        DRAW_OVERRIDES.clear();
    }

    /** 将存储的帧参数写入渲染状态:完整驱动 setupAnim(游泳/下蹲/使用物品/挥击/行走/姿态等)。 */
    public static void applyFrameExtra(LivingEntityRenderState state, StatueFrameData.FrameExtra e) {
        if (e == null) return;
        state.ageInTicks = e.ageInTicks();
        state.walkAnimationPos = e.walkPos();
        state.walkAnimationSpeed = e.walkSpeed();
        int poseOrdinal = e.poseOrdinal() & 0xFF;
        Pose[] poses = Pose.values();
        if (poseOrdinal >= 0 && poseOrdinal < poses.length) {
            state.pose = poses[poseOrdinal];
        }
        state.isInWater = (e.flags() & StatueFrameData.FrameExtra.FLAG_IN_WATER) != 0;
        state.isAutoSpinAttack = (e.flags() & StatueFrameData.FrameExtra.FLAG_SPIN_ATTACK) != 0;
        state.isBaby = (e.flags() & StatueFrameData.FrameExtra.FLAG_BABY) != 0;
        state.scale = e.scale();
        state.deathTime = e.deathTime();
        // 头部转头:state.yRot 已是"相对身体"的角度(wrapDegrees(yHeadRot - bodyRot)),
        // xRot 为头部 pitch,写回渲染状态由 setupAnim 驱动 head 骨骼。
        // 注意:绝不要覆盖 state.bodyRot —— 它是雕像整体朝向(renderer 用 180-bodyRot 旋转),
        // 若写回捕获帧瞬时 bodyRot 会使身体朝向偏移、并压缩头部相对转动幅度。
        state.xRot = e.xRot();
        state.yRot = e.yRot();
        if (state instanceof HumanoidRenderState h) {
            h.swimAmount = e.swimAmount();
            h.isUsingItem = (e.flags() & StatueFrameData.FrameExtra.FLAG_USING_ITEM) != 0;
            h.isCrouching = (e.flags() & StatueFrameData.FrameExtra.FLAG_CROUCHING) != 0;
            h.isFallFlying = (e.flags() & StatueFrameData.FrameExtra.FLAG_FALL_FLYING) != 0;
            h.ticksUsingItem = h.isUsingItem ? e.useTicks() : 0;
            h.isVisuallySwimming = state.pose == Pose.SWIMMING;
        }
        if (state instanceof ArmedEntityRenderState a) {
            a.attackTime = e.attackTime();
            HumanoidArm[] arms = HumanoidArm.values();
            if (e.mainArm() >= 0 && e.mainArm() < arms.length) a.mainArm = arms[e.mainArm()];
            if (e.attackArm() >= 0 && e.attackArm() < arms.length) a.attackArm = arms[e.attackArm()];
            HumanoidModel.ArmPose[] armPoses = HumanoidModel.ArmPose.values();
            if (e.rightArmPose() >= 0 && e.rightArmPose() < armPoses.length) {
                a.rightArmPose = armPoses[e.rightArmPose()];
            }
            if (e.leftArmPose() >= 0 && e.leftArmPose() < armPoses.length) {
                a.leftArmPose = armPoses[e.leftArmPose()];
            }
        }
        StatueAnimationExtraClient.apply(state, e.typeExtra(), state.pose);
    }

    /** 烘焙:把源模型(刚 setupAnim 完)的全部骨骼姿态复制到克隆模型。 */
    public static void copyPose(ModelPart from, ModelPart to) {
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
        Map<String, ModelPart> fc = ((ModelPartAccessor) (Object) from).copperEnchant$getChildren();
        Map<String, ModelPart> tc = ((ModelPartAccessor) (Object) to).copperEnchant$getChildren();
        for (Map.Entry<String, ModelPart> entry : fc.entrySet()) {
            ModelPart toChild = tc.get(entry.getKey());
            if (toChild != null) {
                copyPose(entry.getValue(), toChild);
            }
        }
    }

    /** 强制克隆模型全部部位可见。
     *  <p>ModelPart.render 遇 visible=false 会直接跳过该子树(整尊雕像透明/缺部位)。
     *  可见性回放已被移除以避免捕获失败的 vis 把部位隐藏,此处再每帧兜底强制可见,
     *  保证动画播放期间雕像绝不被隐藏。 */
    public static void setAllVisible(ModelPart root) {
        root.visible = true;
        for (ModelPart child : ((ModelPartAccessor) (Object) root).copperEnchant$getChildren().values()) {
            setAllVisible(child);
        }
    }

    /** 按存储帧逐骨骼回放:旋转 + 位移 + 可见性全部还原到克隆模型(独立空 setupAnim,绘制不被改写)。 */
    public static boolean applyStoredPose(Model<?> model, StatueFrames frames, int index) {
        List<String> paths = frames.paths();
        List<StatueFrameData> frameList = frames.frames();
        if (paths == null || frameList == null || frameList.isEmpty()) return false;
        if (index < 0 || index >= frameList.size()) return false;
        StatueFrameData fd = frameList.get(index);
        short[] rotations = fd.rotations();
        if (rotations == null || rotations.length < paths.size() * 3) return false;

        // 结构一致性校验:克隆模型(statue)的部件路径集合必须与帧数据 paths 完全一致才可逐骨骼回放。
        // 小模型(baby)/不同变体会导致结构(路径集合)不一致,此时逐骨骼按名字错位会渲染错乱,
        // 应回退到 setupAnim 烘焙而非盲目回放。
        List<String> cloneList = new ArrayList<>(paths.size());
        collectPaths(model.root(), "", cloneList);
        if (!new java.util.HashSet<>(cloneList).equals(new java.util.HashSet<>(paths))) {
            return false;
        }

        Map<String, Integer> pathIndex = new HashMap<>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            pathIndex.put(paths.get(i), i);
        }
        applyStoredByName(model.root(), "", pathIndex, rotations, fd.tra(), fd.vis());
        return true;
    }

    private static void applyStoredByName(ModelPart part, String path,
                                          Map<String, Integer> pathIndex, short[] rotations,
                                          short[] tra, byte[] vis) {
        Integer idx = pathIndex.get(path);
        if (idx != null) {
            int base = idx * 3;
            part.xRot = StatueFrameData.unquantize(rotations[base]);
            part.yRot = StatueFrameData.unquantize(rotations[base + 1]);
            part.zRot = StatueFrameData.unquantize(rotations[base + 2]);
            if (tra != null && base + 2 < tra.length) {
                part.x = StatueFrameData.unquantize(tra[base]);
                part.y = StatueFrameData.unquantize(tra[base + 1]);
                part.z = StatueFrameData.unquantize(tra[base + 2]);
            }
            // 不写 part.visible:可见性回放在穿装备/结构偏差时易把部位设成隐藏导致模型消失/透明。
            // 需要持物收手等"隐藏"效果时,由 shared layer 用渲染状态参数驱动,骨骼回放仅负责旋转/位移。
        }
        Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).copperEnchant$getChildren();
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            applyStoredByName(entry.getValue(),
                path.isEmpty() ? entry.getKey() : path + "/" + entry.getKey(), pathIndex, rotations, tra, vis);
        }
    }

    /** 旧格式仅回放旋转(无位移/可见性数据)。 */
    public static boolean applyStoredFrame(Model<?> model, StatueFrames frames, int index) {
        return applyStoredPose(model, frames, index);
    }

    /** 由雕像渲染器在提交阶段注册:该状态对象的模型节点在绘制阶段应回放存储帧姿态(旧格式)。 */
    public static void registerStoredFrameOverride(Object renderState, StatueFrames frames, int index) {
        if (renderState == null || frames == null) return;
        DRAW_OVERRIDES.put(renderState, new OverrideEntry(frames, index));
    }

    /** 由 {@code ModelFeatureRendererMixin} 在 setupAnim 之后调用,统一身体与盔甲等图层姿态(旧格式)。 */
    public static void tryApplyDrawOverride(Model<?> model, Object renderState) {
        OverrideEntry entry = DRAW_OVERRIDES.get(renderState);
        if (entry == null) return;
        if (!(model instanceof EntityModel<?>)) return;
        applyStoredFrame(model, entry.frames(), entry.index());
    }

    /** 每帧开始时清空绘制时覆盖注册表(由 {@code LevelRendererMixin} 调用)。 */
    public static void beginFrame() {
        DRAW_OVERRIDES.clear();
    }
}
