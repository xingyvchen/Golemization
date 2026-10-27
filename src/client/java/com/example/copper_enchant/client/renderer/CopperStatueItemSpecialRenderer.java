package com.example.copper_enchant.client.renderer;

import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import com.example.copper_enchant.util.StatuePlayerFactory;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class CopperStatueItemSpecialRenderer implements SpecialModelRenderer<LivingEntity> {

    private final EntityRenderDispatcher entityRenderer;

    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(1);

    public CopperStatueItemSpecialRenderer() {
        this.entityRenderer = Minecraft.getInstance().getEntityRenderDispatcher();
    }

    @Override
    public LivingEntity extractArgument(ItemStack stack) {
        TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return null;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        try {
            CompoundTag blockEntityTag = data.getUnsafe();

            Optional<CompoundTag> entityTagOpt = blockEntityTag.getCompound("entity");
            if (entityTagOpt.isPresent()) {
                ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), entityTagOpt.get());
                Entity entity = EntityType.loadEntityRecursive(input, level, EntitySpawnReason.LOAD, e -> e);
                if (entity instanceof LivingEntity living) {
                    living.setId(ENTITY_ID_COUNTER.getAndIncrement());
                    return living;
                }
                return null;
            }

            Optional<CompoundTag> profileTagOpt = blockEntityTag.getCompound("playerGameProfile");
            if (profileTagOpt.isPresent()) {
                GameProfile profile = CopperStatueBlockEntity.readGameProfile(profileTagOpt.get());
                if (profile != null) {
                    LivingEntity player = StatuePlayerFactory.create(level, profile);
                    if (player != null) {
                        player.setId(ENTITY_ID_COUNTER.getAndIncrement());
                        return player;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void submit(LivingEntity entity, PoseStack poseStack, SubmitNodeCollector collector,
                       int light, int overlay, boolean glint, int tint) {
        if (entity == null) return;

        entity.walkAnimation.stop();
        entity.setYRot(0);
        entity.yRotO = 0;
        entity.setYHeadRot(0);
        entity.yHeadRotO = 0;
        entity.setYBodyRot(0);
        entity.yBodyRotO = 0;
        entity.setXRot(0);
        entity.xRotO = 0;
        entity.setPos(0, 0, 0);
        entity.xo = 0;
        entity.yo = 0;
        entity.zo = 0;

        EntityRenderState renderState = entityRenderer.extractEntity(entity, 0);
        if (!(renderState instanceof LivingEntityRenderState livingState)) return;
        livingState.ageInTicks = 0;

        EntityRenderer<?, ?> renderer = entityRenderer.getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer livingRenderer)) return;
        EntityModel model = livingRenderer.getModel();
        Identifier texture = livingRenderer.getTextureLocation(livingState);

        // 复用 CopperStatueBlockEntityRenderer 在世界上已验证的正立变换:
        // scale(scale) -> scale(-1,-1,1) -> translate(0,-1.501,0)。
        // 不调用 entityRenderer.submit,避开它那套世界旋转导致的不可控翻转/透明。
        // 物品展示用静态站立姿态即可,无需回放动画。
        model.setupAnim(livingState);
        poseStack.pushPose();
        // 与方块实体渲染器一致,精确镜像原版 LivingEntityRenderer.submit 的变换顺序:
        //   scale(entityScale) -> 幻翼 xRot俯仰 -> scale(-1,-1,1) -> 幻翼 size缩放+translate -> translate(0,-1.501,0)。
        // 幻翼的 translate(0,1.3125,0.1875) 必须在翻转/缩放之后,保证整体结构位置正确。
        poseStack.scale(livingState.scale, livingState.scale, livingState.scale);
        if (livingState instanceof PhantomRenderState phantom) {
            // 幻翼(PhantomRenderer.setupRotations):按 xRot 做飞行俯仰倾斜
            poseStack.mulPose(Axis.XP.rotationDegrees(phantom.xRot));
        }
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        if (livingState instanceof PhantomRenderState phantom) {
            // 幻翼(PhantomRenderer.scale):身体随 size(1/2/3)缩放,并向重心上移/前移
            float sizeScale = 1.0F + 0.15F * phantom.size;
            poseStack.scale(sizeScale, sizeScale, sizeScale);
            poseStack.translate(0.0F, 1.3125F, 0.1875F);
        }
        poseStack.translate(0.0F, -1.501F, 0.0F);
        collector.submitModel(model, livingState, poseStack, model.renderType(texture),
            light, overlay, -1, null, 0, null);
        poseStack.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> consumer) {
        consumer.accept(new Vector3f(-8.0f, 0.0f, -8.0f));
        consumer.accept(new Vector3f(8.0f, 32.0f, 8.0f));
    }

    public record Unbaked() implements SpecialModelRenderer.Unbaked<LivingEntity> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(Unbaked::new);

        @Override
        public SpecialModelRenderer<LivingEntity> bake(BakingContext context) {
            return new CopperStatueItemSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<LivingEntity>> type() {
            return MAP_CODEC;
        }
    }
}
