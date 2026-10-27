package com.example.copper_enchant.blockentity;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.block.CopperStatueBlock;
import com.example.copper_enchant.data.StatueFrameCache;
import com.example.copper_enchant.data.StatueFrames;
import com.example.copper_enchant.network.StatueFrameCursorPayload;
import com.example.copper_enchant.registry.ModBlockEntities;
import com.example.copper_enchant.registry.ModBlocks;
import com.example.copper_enchant.util.StatuePlayerFactory;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.google.common.collect.ArrayListMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Nameable;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class CopperStatueBlockEntity extends BlockEntity implements Nameable {

    public record StatueAnimationSnapshot(float walkPos, float walkSpeed, Pose pose,
                                          float xRot, float yBodyRot, float yHeadRot, int tickAge,
                                          CompoundTag extra) {
    }

    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(1);

    /** 服务端:转化时的傀儡方块位置(用于帧到达后立即投递)。 */
    private static final Map<UUID, ServerLevel> FRAME_TARGET_LEVELS = new HashMap<>();
    private static final Map<UUID, BlockPos> FRAME_TARGET_POS = new HashMap<>();

    /** 服务端:已放置的雕像(按维度),用于环境动画(亡灵白天燃烧粒子等)周期检查。 */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> ENV_STATUES = new HashMap<>();
    private static long lastEnvTick = 0;

    private LivingEntity storedEntity;
    private float bodyRot;
    private float headRot;
    private String entityTypeKey;
    private String blockNameKey;
    private String playerName;
    private String playerUuid;
    private CompoundTag playerProfileTag;
    private StatueAnimationSnapshot statueAnimation;
    private List<StatueAnimationSnapshot> statueAnimationHistory;
    private StatueFrames statueFrames;
    private int currentFrame = -1;
    private final Map<EquipmentSlot, ItemStack> capturedEquipment = new EnumMap<>(EquipmentSlot.class);
    private List<MobEffectInstance> capturedEffects;
    private ValueInput pendingEntityInput;
    private GameProfile pendingPlayerProfile;

    public CopperStatueBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COPPER_STATUE, pos, state);
    }

    public void setEntity(LivingEntity entity) {
        setEntity(entity, null, null);
    }

    public void setEntity(LivingEntity entity, StatueAnimationSnapshot snapshot) {
        setEntity(entity, snapshot, null);
    }

    public void setEntity(LivingEntity entity, StatueAnimationSnapshot snapshot,
                          List<StatueAnimationSnapshot> history) {
        this.bodyRot = entity.getYRot();
        this.headRot = entity.getYRot();
        this.entityTypeKey = entity.getType().getDescriptionId();
        this.statueAnimation = snapshot;
        this.statueAnimationHistory = history;
        if (entity instanceof Player player) {
            this.playerName = player.getGameProfile().name();
            if (this.playerName != null && this.playerName.isEmpty()) {
                this.playerName = null;
            }
            this.playerUuid = player.getUUID().toString();
            this.playerProfileTag = writeGameProfile(player.getGameProfile());
            captureEquipmentAndEffects(entity);
            this.storedEntity = null;
        } else {
            this.storedEntity = entity;
            this.playerName = null;
            this.playerUuid = null;
            this.playerProfileTag = null;
            captureEffects(entity); // 普通生物也保留其 buff 效果,供斧头右击还原时返还
        }
        if (level != null && !level.getBlockState(worldPosition).isAir()) {
            this.blockNameKey = level.getBlockState(worldPosition).getBlock().getDescriptionId();
        }
        setChanged();
    }

    public LivingEntity getEntity() {
        return storedEntity;
    }

    private void captureEquipmentAndEffects(LivingEntity entity) {
        capturedEquipment.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                capturedEquipment.put(slot, stack.copy());
            }
        }
        captureEffects(entity);
    }

    /** 仅捕获活动效果(装备仍由 storedEntity 实体自承载)。普通生物也须保留,供斧头右击还原时返还。 */
    private void captureEffects(LivingEntity entity) {
        if (capturedEffects == null) {
            capturedEffects = new ArrayList<>();
        }
        capturedEffects.clear();
        for (MobEffectInstance effect : entity.getActiveEffects()) {
            if (effect != null) {
                capturedEffects.add(new MobEffectInstance(effect));
            }
        }
    }

    public void applyCapturedState(LivingEntity target) {
        if (target == null) return;
        for (Map.Entry<EquipmentSlot, ItemStack> entry : capturedEquipment.entrySet()) {
            target.setItemSlot(entry.getKey(), entry.getValue().copy());
        }
        applyCapturedEffects(target);
    }

    /** 仅返还傀儡化前保留的效果(玩家还原真身时用,不覆盖其当前装备)。 */
    public void applyCapturedEffects(LivingEntity target) {
        if (target == null || capturedEffects == null) return;
        for (MobEffectInstance effect : capturedEffects) {
            if (effect != null) {
                target.addEffect(new MobEffectInstance(effect));
            }
        }
    }

    public StatueAnimationSnapshot getStatueAnimation() {
        return statueAnimation;
    }

    public List<StatueAnimationSnapshot> getStatueAnimationHistory() {
        return statueAnimationHistory;
    }

    public StatueFrames getStatueFrames() {
        return statueFrames;
    }

    public void setStatueFrames(StatueFrames frames) {
        this.statueFrames = frames;
        CopperEnchantMod.LOGGER.info("[StatueBE] 帧已写入方块实体 pos={} 帧数={}",
            worldPosition,
            frames == null || frames.frames() == null ? 0 : frames.frames().size());
        setChanged();
        if (level != null && !level.isClientSide()) {
            syncToClients();
        }
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public UUID getEntityUuid() {
        if (storedEntity != null) {
            return storedEntity.getUUID();
        }
        if (playerUuid != null) {
            try {
                return UUID.fromString(playerUuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    /** 服务端:转化时记录傀儡方块位置,帧上报到达后立即写入方块实体并同步给客户端。 */
    public static void registerFrameTarget(UUID uuid, ServerLevel level, BlockPos pos) {
        if (uuid == null) return;
        FRAME_TARGET_LEVELS.put(uuid, level);
        FRAME_TARGET_POS.put(uuid, pos.immutable());
    }

    public static void unregisterFrameTarget(UUID uuid) {
        if (uuid == null) return;
        FRAME_TARGET_LEVELS.remove(uuid);
        FRAME_TARGET_POS.remove(uuid);
    }

    public static void deliverFrames(UUID uuid, StatueFrames frames) {
        if (uuid == null || frames == null) return;
        ServerLevel level = FRAME_TARGET_LEVELS.get(uuid);
        BlockPos pos = FRAME_TARGET_POS.get(uuid);
        if (level != null && pos != null && level.isLoaded(pos)
                && level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be
                && uuid.equals(be.getEntityUuid())) {
            CopperEnchantMod.LOGGER.info("[StatueBE] 投递帧到雕像 pos={} uuid={}", pos, uuid);
            be.setStatueFrames(frames);
        } else {
            CopperEnchantMod.LOGGER.warn("[StatueBE] 未找到帧目标,帧转入缓存等待消费 uuid={} level={} pos={}",
                uuid, level, pos);
        }
        unregisterFrameTarget(uuid);
    }

    /** 环境状态注册:雕像放置后由 createAndPlace 调用。 */
    public static void registerEnvStatue(ServerLevel level, BlockPos pos) {
        ENV_STATUES.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
    }

    public static void unregisterEnvStatue(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = ENV_STATUES.get(level.dimension());
        if (set != null) {
            set.remove(pos);
            if (set.isEmpty()) {
                ENV_STATUES.remove(level.dimension());
            }
        }
    }

    /** 服务端每 1 秒检查:白天露天环境下的亡灵雕像生成火焰粒子(对应亡灵在阳光下燃烧的动画状态)。 */
    public static void tickEnvStatues(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastEnvTick < 1000L) return;
        lastEnvTick = now;
        for (ServerLevel level : server.getAllLevels()) {
            Set<BlockPos> set = ENV_STATUES.get(level.dimension());
            if (set == null || set.isEmpty()) continue;
            boolean day = level.getSkyDarken() < 4;
            Iterator<BlockPos> it = set.iterator();
            while (it.hasNext()) {
                BlockPos pos = it.next();
                if (!level.isLoaded(pos)) continue;
                if (!(level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be) || be.isRemoved()) {
                    it.remove();
                    continue;
                }
                if (!day || !level.canSeeSkyFromBelowWater(pos.above())) continue;
                LivingEntity entity = be.getEntity();
                if (entity instanceof AbstractSkeleton || entity instanceof Zombie) {
                    RandomSource random = level.getRandom();
                    level.addParticle(ParticleTypes.FLAME,
                        pos.getX() + 0.3 + random.nextDouble() * 0.4,
                        pos.getY() + 0.5 + random.nextDouble() * 0.5,
                        pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                        0.0, 0.02, 0.0);
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            unregisterFrameTarget(getEntityUuid());
            StatueFrameCache.remove(getEntityUuid());
            if (level instanceof ServerLevel serverLevel) {
                unregisterEnvStatue(serverLevel, worldPosition);
            }
        }
        // 注意:不能在这里清空 statueFrames。方块的掉落 NBT(含帧)在 setBlock(air)=setRemoved
        // 之前由 getDrops 从本方块实体现场采集;一旦此处提前置 null,恰好在该路径执行时
        // 掉落物就会丢失动画帧("放出来帧数没有")。方块实体本身在移除后失去引用即被 GC,
        // 帧随对象回收,而静态缓存(FRAME_TARGET_*/StatueFrameCache)已在上方清理,无泄漏。
    }

    /** 放置/加载后若本体没有帧,尝试从服务端待消费缓存中找回(弥补"刚转化就被挖掘"的竞态)。 */
    private void ensureFramesFromCache() {
        if (statueFrames == null || statueFrames.isEmpty()) {
            StatueFrames cached = StatueFrameCache.consume(getEntityUuid());
            if (cached != null) {
                statueFrames = cached;
                CopperEnchantMod.LOGGER.info("[StatueBE] 放置后从缓存补充帧 pos={} 帧数={}",
                    worldPosition, cached.frames() == null ? 0 : cached.frames().size());
            }
        }
    }

    /** 木剑交互:前进一帧并锁定;无帧时提示并返回 PASS。 */
    public InteractionResult advanceOneFrame(ServerLevel level, Player player) {
        if (statueFrames == null) {
            statueFrames = StatueFrameCache.consume(getEntityUuid());
            if (statueFrames != null) {
                CopperEnchantMod.LOGGER.info("[StatueBE] 从缓存消费帧 pos={} 帧数={}", worldPosition, statueFrames.frames().size());
            }
        }
        if (statueFrames == null || statueFrames.frames().isEmpty()) {
            CopperEnchantMod.LOGGER.warn("[StatueBE] 木剑右击但无帧可用 pos={} uuid={}", worldPosition, getEntityUuid());
            if (player != null) {
                player.sendSystemMessage(
                    Component.literal("此雕像没有动画帧数据,需用傀儡化附魔铜剑重新触发转化生成新雕像"));
            }
            return InteractionResult.PASS;
        }
        int total = statueFrames.frames().size();
        currentFrame = (currentFrame + 1) % total;
        if (currentFrame < 0) {
            currentFrame = 0;
        }
        CopperEnchantMod.LOGGER.info("[StatueBE] 木剑前进一帧 pos={} 帧={}/{}", worldPosition, currentFrame + 1, total);
        setChanged();
        if (player instanceof ServerPlayer serverPlayer) {
            sendCursorTo(serverPlayer);
        }
        syncToClients();
        return InteractionResult.SUCCESS;
    }

    public boolean hasFrames() {
        return statueFrames != null && !statueFrames.isEmpty();
    }

    public int getTotalFrames() {
        return statueFrames == null || statueFrames.frames() == null ? 0 : statueFrames.frames().size();
    }

    /** 输入框提交:跳转到指定帧(1 基),范围由服务端按实际存储帧数校验。 */
    public void setFrame(ServerLevel level, ServerPlayer player, int frame) {
        if (!hasFrames()) return;
        int total = statueFrames.frames().size();
        if (frame < 1) {
            frame = 1;
        }
        if (frame > total) {
            frame = total;
        }
        currentFrame = frame - 1;
        setChanged();
        sendCursorTo(player);
        syncToClients();
        CopperEnchantMod.LOGGER.info("[StatueBE] 跳转帧 pos={} 帧={}/{}", worldPosition, frame, total);
    }

    /** 给指定玩家发送当前帧进度(用于物品栏上方 HUD 提示)。 */
    public void sendCursorTo(ServerPlayer player) {
        if (player == null || !hasFrames()) return;
        ServerPlayNetworking.send(player,
            new StatueFrameCursorPayload(currentFrame + 1, statueFrames.frames().size()));
    }

    public float getBodyRot() {
        return bodyRot;
    }

    public float getHeadRot() {
        return headRot;
    }

    public void rotateClockwise(float degrees) {
        bodyRot = (bodyRot + degrees) % 360.0f;
        headRot = (headRot + degrees) % 360.0f;
        if (storedEntity != null) {
            storedEntity.setYRot(bodyRot);
            storedEntity.yRotO = bodyRot;
            storedEntity.setYHeadRot(headRot);
            storedEntity.yHeadRotO = headRot;
            storedEntity.setYBodyRot(bodyRot);
            storedEntity.yBodyRotO = bodyRot;
        }
        if (level != null) {
            setChanged();
            if (!level.isClientSide()) {
                syncToClients();
            }
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if ((storedEntity != null || playerName != null) && level != null) {
            CompoundTag tag = saveWithoutMetadata(level.registryAccess());
            builder.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(getType(), tag));
            builder.set(DataComponents.CUSTOM_NAME, getName());
        }
    }

    public boolean restore(ServerLevel level, BlockPos pos) {
        if (playerName != null || playerUuid != null) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            String uuidStr = playerUuid;
            storedEntity = null;
            if (uuidStr != null) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
                    if (player != null && player.level() == level && !player.isRemoved() && !player.isDeadOrDying()) {
                        player.connection.teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, bodyRot, 0.0f);
                        applyCapturedEffects(player); // 返还傀儡化前保留的 buff
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return true;
        }

        if (storedEntity == null) return false;

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        output.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(storedEntity.getType()).toString());
        storedEntity.saveWithoutId(output);

        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult());
        Entity loaded = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, e -> e);
        if (!(loaded instanceof LivingEntity living)) return false;

        living.snapTo(pos.getX() + 0.5, (double) pos.getY(), pos.getZ() + 0.5, bodyRot, living.getXRot());
        living.yHeadRot = bodyRot;
        living.yBodyRot = bodyRot;

        living.setInvulnerable(false);
        living.setNoGravity(false);
        living.setSilent(false);
        if (living instanceof Mob mob) {
            mob.setNoAi(false);
        }

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        applyCapturedState(living); // 返还傀儡化前保留的装备(若有) + buff 效果
        level.addFreshEntity(living);

        storedEntity = null;
        return true;
    }

    @Override
    public Component getName() {
        if (storedEntity != null && storedEntity.hasCustomName()) {
            return storedEntity.getCustomName();
        }
        Component entityName;
        if (playerName != null && !playerName.isEmpty()) {
            entityName = Component.literal(playerName);
        } else if (entityTypeKey != null && !entityTypeKey.isEmpty()) {
            entityName = Component.translatable(entityTypeKey);
        } else {
            entityName = Component.literal("");
        }
        return Component.translatable(resolveBlockNameKey(), entityName);
    }

    private String resolveBlockNameKey() {
        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            if (state != null && !state.isAir() && state.getBlock() instanceof CopperStatueBlock) {
                return state.getBlock().getDescriptionId();
            }
        }
        if (blockNameKey != null && !blockNameKey.isEmpty()) {
            return blockNameKey;
        }
        return "block.copper_enchant.copper_statue";
    }

    @Override
    public Component getCustomName() {
        return getName();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putFloat("bodyRot", bodyRot);
        output.putFloat("headRot", headRot);
        if (entityTypeKey != null) {
            output.putString("entityTypeKey", entityTypeKey);
        }
        if (blockNameKey != null) {
            output.putString("blockNameKey", blockNameKey);
        }
        if (playerName != null) {
            output.putString("playerName", playerName);
        }
        if (playerUuid != null) {
            output.putString("playerUuid", playerUuid);
        }
        if (playerProfileTag != null) {
            output.store("playerGameProfile", CompoundTag.CODEC, playerProfileTag);
        }
        if (statueAnimation != null) {
            CompoundTag snapshotTag = new CompoundTag();
            snapshotTag.putFloat("walk_pos", statueAnimation.walkPos());
            snapshotTag.putFloat("walk_speed", statueAnimation.walkSpeed());
            snapshotTag.putString("pose", statueAnimation.pose().name());
            snapshotTag.putFloat("xrot", statueAnimation.xRot());
            snapshotTag.putFloat("ybodyrot", statueAnimation.yBodyRot());
            snapshotTag.putFloat("yheadrot", statueAnimation.yHeadRot());
            snapshotTag.putInt("tick_age", statueAnimation.tickAge());
            if (statueAnimation.extra() != null) {
                snapshotTag.put("extra", statueAnimation.extra());
            }
            output.store("statue_animation", CompoundTag.CODEC, snapshotTag);
        }
        if (statueAnimationHistory != null && !statueAnimationHistory.isEmpty()) {
            ValueOutput historyOutput = output.child("statue_animation_history");
            historyOutput.putInt("count", statueAnimationHistory.size());
            for (int i = 0; i < statueAnimationHistory.size(); i++) {
                StatueAnimationSnapshot snap = statueAnimationHistory.get(i);
                ValueOutput moment = historyOutput.child("m" + i);
                moment.putFloat("w", snap.walkPos());
                moment.putFloat("s", snap.walkSpeed());
                moment.putString("p", snap.pose().name());
                moment.putFloat("x", snap.xRot());
                moment.putFloat("yb", snap.yBodyRot());
                moment.putFloat("yh", snap.yHeadRot());
                moment.putInt("a", snap.tickAge());
                if (snap.extra() != null) {
                    moment.store("e", CompoundTag.CODEC, snap.extra());
                }
            }
        }
        if (statueFrames == null) {
            statueFrames = StatueFrameCache.consume(getEntityUuid());
        }
        if (statueFrames != null && !statueFrames.isEmpty()) {
            StatueFrames.save(output, statueFrames);
        }
        output.putInt("statue_current_frame", currentFrame);
        if (storedEntity != null) {
            ValueOutput entityOutput = output.child("entity");
            String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(storedEntity.getType()).toString();
            entityOutput.putString("id", entityId);
            storedEntity.saveWithoutId(entityOutput);
        }

        for (Map.Entry<EquipmentSlot, ItemStack> entry : capturedEquipment.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                output.store("equipment_" + entry.getKey().getName(), ItemStack.CODEC, entry.getValue());
            }
        }
        if (capturedEffects != null && !capturedEffects.isEmpty()) {
            output.putInt("effect_count", capturedEffects.size());
            for (int i = 0; i < capturedEffects.size(); i++) {
                output.store("effect_" + i, MobEffectInstance.CODEC, capturedEffects.get(i));
            }
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        bodyRot = input.getFloatOr("bodyRot", 0f);
        headRot = input.getFloatOr("headRot", 0f);
        entityTypeKey = input.getStringOr("entityTypeKey", "");
        blockNameKey = input.getStringOr("blockNameKey", "");
        String loadedPlayerName = input.getStringOr("playerName", "");
        playerName = loadedPlayerName.isEmpty() ? null : loadedPlayerName;
        String loadedPlayerUuid = input.getStringOr("playerUuid", "");
        playerUuid = loadedPlayerUuid.isEmpty() ? null : loadedPlayerUuid;
        playerProfileTag = input.read("playerGameProfile", CompoundTag.CODEC).orElse(null);

        CompoundTag snapshotTag = input.read("statue_animation", CompoundTag.CODEC).orElse(null);
        if (snapshotTag != null) {
            float walkPos = snapshotTag.getFloatOr("walk_pos", 0.0f);
            float walkSpeed = snapshotTag.getFloatOr("walk_speed", 0.0f);
            Pose pose = Pose.STANDING;
            try {
                pose = Pose.valueOf(snapshotTag.getStringOr("pose", "STANDING"));
            } catch (IllegalArgumentException ignored) {
            }
            statueAnimation = new StatueAnimationSnapshot(walkPos, walkSpeed, pose,
                snapshotTag.getFloatOr("xrot", 0.0f),
                snapshotTag.getFloatOr("ybodyrot", 0.0f),
                snapshotTag.getFloatOr("yheadrot", 0.0f),
                snapshotTag.getIntOr("tick_age", 0),
                snapshotTag.getCompound("extra").orElse(null));
        }

        Optional<ValueInput> historyInputOpt = input.child("statue_animation_history");
        if (historyInputOpt.isPresent()) {
            ValueInput historyInput = historyInputOpt.get();
            int count = historyInput.getIntOr("count", 0);
            if (count > 0) {
                List<StatueAnimationSnapshot> history = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    Optional<ValueInput> momentOpt = historyInput.child("m" + i);
                    if (momentOpt.isEmpty()) break;
                    ValueInput moment = momentOpt.get();
                    float walkPos = moment.getFloatOr("w", 0.0f);
                    float walkSpeed = moment.getFloatOr("s", 0.0f);
                    Pose pose = Pose.STANDING;
                    try {
                        pose = Pose.valueOf(moment.getStringOr("p", "STANDING"));
                    } catch (IllegalArgumentException ignored) {
                    }
                    history.add(new StatueAnimationSnapshot(walkPos, walkSpeed, pose,
                        moment.getFloatOr("x", 0.0f),
                        moment.getFloatOr("yb", 0.0f),
                        moment.getFloatOr("yh", 0.0f),
                        moment.getIntOr("a", 0),
                        moment.read("e", CompoundTag.CODEC).orElse(null)));
                }
                if (!history.isEmpty()) {
                    statueAnimationHistory = history;
                }
            }
        }

        statueFrames = StatueFrames.load(input);
        currentFrame = input.getIntOr("statue_current_frame", -1);

        capturedEquipment.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            input.read("equipment_" + slot.getName(), ItemStack.CODEC)
                .ifPresent(stack -> capturedEquipment.put(slot, stack));
        }
        int effectCount = input.getIntOr("effect_count", 0);
        if (effectCount > 0) {
            capturedEffects = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                input.read("effect_" + i, MobEffectInstance.CODEC)
                    .ifPresent(capturedEffects::add);
            }
        } else {
            capturedEffects = null;
        }

        if (playerName != null || playerUuid != null) {
            GameProfile profile = playerProfileTag == null ? null : readGameProfile(playerProfileTag);
            if (profile != null) {
                if (level != null) {
                    storedEntity = StatuePlayerFactory.create(level, profile);
                    applyCapturedState(storedEntity);
                } else {
                    pendingPlayerProfile = profile;
                }
            }
            ensureFramesFromCache();
            return;
        }

        Optional<ValueInput> entityInputOpt = input.child("entity");
        if (entityInputOpt.isPresent()) {
            if (level != null) {
                loadStoredEntity(entityInputOpt.get());
            } else {
                pendingEntityInput = entityInputOpt.get();
            }
        }
        ensureFramesFromCache();
    }

    private void loadStoredEntity(ValueInput entityInput) {
        try {
            Entity loaded = EntityType.loadEntityRecursive(entityInput, level, EntitySpawnReason.LOAD, e -> e);
            if (loaded instanceof LivingEntity living) {
                living.setId(ENTITY_ID_COUNTER.getAndIncrement());
                storedEntity = living;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level == null) return;
        if (storedEntity == null && pendingEntityInput != null) {
            ValueInput entityInput = pendingEntityInput;
            pendingEntityInput = null;
            loadStoredEntity(entityInput);
        }
        if (storedEntity == null && pendingPlayerProfile != null) {
            GameProfile profile = pendingPlayerProfile;
            pendingPlayerProfile = null;
            storedEntity = StatuePlayerFactory.create(level, profile);
            applyCapturedState(storedEntity);
        }
        ensureFramesFromCache();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 从方块物品放置(setPlacedBy)后调用:下一个服务器 tick 把方块实体数据推给附近客户端。
     *  <p>原因:客户端在已加载区块内放置雕像时只收到方块状态、收不到块实体数据;
     *  若不在放置时同步,客户端雕像就"帧数没有"(姿态/逐帧数据缺失)。
     *  <p>延迟一 tick 是为了躲过 setPlacedBy 与 loadWithComponents 的相对先后,确保推送时数据已就绪;
     *  区块加载/世界生成不经过 setPlacedBy,天然不推送(客户端已随区块取到数据)。 */
    public void schedulePlacementDataSync() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;
        server.execute(() -> {
            if (isRemoved() || level != serverLevel) return;
            ensureFramesFromCache(); // 认领"刚转化就被挖掘"竞态下稍后到达的缓存帧
            syncToClients();
        });
    }

    /** 显式把方块实体数据(帧/当前帧/旋转等)推送给附近玩家;26.2 的 sendBlockUpdated 不携带实体数据。 */
    public void syncToClients() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        ClientboundBlockEntityDataPacket packet = getUpdatePacket();
        Vec3 center = Vec3.atCenterOf(worldPosition);
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(center) <= 4096.0) {
                player.connection.send(packet);
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    public static void createAndPlace(ServerLevel level, BlockPos pos, LivingEntity entity,
                                      StatueAnimationSnapshot snapshot,
                                      List<StatueAnimationSnapshot> history) {
        if (!(entity instanceof Player)) {
            entity.setInvulnerable(true);
            entity.setNoGravity(true);
            entity.setSilent(true);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.stopRiding();
        entity.ejectPassengers();
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }

        BlockState state = ModBlocks.COPPER_STATUE.defaultBlockState()
            .setValue(CopperStatueBlock.FACING, entity.getDirection())
            .setValue(CopperStatueBlock.WATERLOGGED, false);

        level.setBlock(pos, state, 3);

        if (level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be) {
            be.setEntity(entity, snapshot, history);
            registerFrameTarget(entity.getUUID(), level, pos);
            registerEnvStatue(level, pos);
            be.syncToClients();
        }
    }

    public static CompoundTag writeGameProfile(GameProfile profile) {
        CompoundTag tag = new CompoundTag();
        UUID id = profile.id();
        if (id != null) {
            tag.putString("id", id.toString());
        }
        String name = profile.name();
        if (name != null) {
            tag.putString("name", name);
        }
        CompoundTag properties = new CompoundTag();
        int index = 0;
        for (Map.Entry<String, Property> entry : profile.properties().entries()) {
            Property property = entry.getValue();
            CompoundTag propertyTag = new CompoundTag();
            propertyTag.putString("name", entry.getKey());
            propertyTag.putString("value", property.value());
            if (property.signature() != null) {
                propertyTag.putString("signature", property.signature());
            }
            properties.put("p" + (index++), propertyTag);
        }
        tag.put("properties", properties);
        return tag;
    }

    public static GameProfile readGameProfile(CompoundTag tag) {
        try {
            UUID id = null;
            String idStr = tag.getString("id").orElse(null);
            if (idStr != null && !idStr.isEmpty()) {
                id = UUID.fromString(idStr);
            }
            String name = tag.getString("name").orElse(null);
            PropertyMap properties = new PropertyMap(ArrayListMultimap.create());
            CompoundTag propertiesTag = tag.getCompoundOrEmpty("properties");
            for (String key : propertiesTag.keySet()) {
                CompoundTag propertyTag = propertiesTag.getCompoundOrEmpty(key);
                String propertyName = propertyTag.getStringOr("name", "");
                String value = propertyTag.getStringOr("value", "");
                if (propertyName.isEmpty() || value.isEmpty()) continue;
                String signature = propertyTag.getString("signature").orElse(null);
                properties.put(propertyName,
                    signature != null ? new Property(propertyName, value, signature) : new Property(propertyName, value));
            }
            return new GameProfile(id, name, properties);
        } catch (Exception e) {
            return null;
        }
    }
}
