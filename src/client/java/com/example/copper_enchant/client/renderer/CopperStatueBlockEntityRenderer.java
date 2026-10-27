package com.example.copper_enchant.client.renderer;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import com.example.copper_enchant.client.mixin.LivingEntityRendererAccessor;
import com.example.copper_enchant.client.mixin.PufferfishRendererAccessor;
import com.example.copper_enchant.client.model.StatueEntityModel;
import com.example.copper_enchant.client.util.StatueAnimationExtraClient;
import com.example.copper_enchant.client.util.StatuePoseCapture;
import com.example.copper_enchant.data.StatueFrameData;
import com.example.copper_enchant.data.StatueFrames;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.CodRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.PufferfishRenderer;
import net.minecraft.client.renderer.entity.SalmonRenderer;
import net.minecraft.client.renderer.entity.TropicalFishRenderer;
import net.minecraft.client.renderer.entity.layers.LivingEntityEmissiveLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class CopperStatueBlockEntityRenderer implements BlockEntityRenderer<CopperStatueBlockEntity, CopperStatueBlockEntityRenderer.CopperStatueRenderState> {

    private final EntityRenderDispatcher entityRenderer;
    private final Map<CopperStatueBlockEntity, StatueEntityModel> cloneModels = new IdentityHashMap<>();
    private StatueFrames loggedFrames;

    public CopperStatueBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.entityRenderer = context.entityRenderer();
    }

    @Override
    public CopperStatueRenderState createRenderState() {
        return new CopperStatueRenderState();
    }

    @Override
    public void extractRenderState(CopperStatueBlockEntity blockEntity, CopperStatueRenderState state,
                                   float partialTick, Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(blockEntity, state, crumblingOverlay);
        state.blockEntity = blockEntity;

        LivingEntity entity = blockEntity.getEntity();
        if (entity != null) {
            entity.walkAnimation.stop();
            entity.setYBodyRot(blockEntity.getBodyRot());
            entity.yBodyRotO = blockEntity.getBodyRot();
            entity.setYHeadRot(blockEntity.getHeadRot());
            entity.yHeadRotO = blockEntity.getHeadRot();
            entity.setPos(0, 0, 0);
            entity.xo = 0;
            entity.yo = 0;
            entity.zo = 0;
            entity.xRotO = entity.getXRot();
            entity.yRotO = entity.getYRot();
            state.entity = entity;
            state.entityRenderState = entityRenderer.extractEntity(entity, partialTick);
            if (state.entityRenderState != null) {
                // 服务端参数快照:作为无存储帧时的降级姿态(也驱动共享模型的 setupAnim)
                CopperStatueBlockEntity.StatueAnimationSnapshot snapshot = blockEntity.getStatueAnimation();
                if (snapshot != null && state.entityRenderState instanceof LivingEntityRenderState livingState) {
                    state.entityRenderState.ageInTicks = snapshot.tickAge();
                    livingState.walkAnimationPos = snapshot.walkPos();
                    livingState.walkAnimationSpeed = snapshot.walkSpeed();
                    livingState.pose = snapshot.pose();
                    livingState.xRot = snapshot.xRot();
                    livingState.bodyRot = snapshot.yBodyRot();
                    livingState.yRot = snapshot.yHeadRot();
                    livingState.isInWater = snapshot.pose() == Pose.SWIMMING;
                    livingState.isAutoSpinAttack = snapshot.pose() == Pose.SPIN_ATTACK;
                    StatueAnimationExtraClient.apply(livingState, snapshot.extra(), snapshot.pose());
                }
                // 环境适配(水中鱼类→游泳姿态;陆地鱼类→扑腾;白天露天亡灵→燃烧):
                // 在这里先按方块环境校正一次,使无帧/快照回退路径也生效;
                // 帧路径在 submit 里 applyFrameExtra 后再校正一次。
                if (state.entityRenderState instanceof LivingEntityRenderState livingState) {
                    applyStatueEnvironment(blockEntity, livingState, state.entityRenderState);
                }
            }
        } else {
            state.entity = null;
            state.entityRenderState = null;
        }
    }

    @Override
    public void submit(CopperStatueRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        if (state.entity == null || state.entityRenderState == null) return;

        EntityRenderer<? super LivingEntity, ?> renderer = entityRenderer.getRenderer(state.entity);
        if (!(renderer instanceof LivingEntityRenderer livingRenderer)) return;
        LivingEntityRenderState livingState = (LivingEntityRenderState) state.entityRenderState;
        Identifier texture = livingRenderer.getTextureLocation(livingState);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - livingState.bodyRot));
        // 陆地上的鱼按原版侧躺贴地(仅转动朝向,不动方块整体位移,避免整块错位)——
        // 必须在 scale(-1,-1,1) 之前的实体空间施加,与原版 setupRotations 一致。
        applyFishOnLand(livingRenderer, livingState, poseStack);
        // 精确镜像原版 EntityRenderDispatcher/LivingEntityRenderer.submit 的变换顺序:
        //   1. scale(entityScale)            -- state.scale 已含 baby 缩放
        //   2. setupRotations(含幻翼 xRot飞行俯仰)
        //   3. scale(-1,-1,1)
        //   4. scale(幻翼 size 尺寸) + translate(0,1.3125,0.1875)   -- PhantomRenderer.scale
        //   5. translate(0,-1.501,0)
        // 关键:幻翼的 translate 必须放在 scale(-1,-1,1) 与尺寸缩放之后,否则偏移会被翻转/放大,
        // 导致幻翼整体结构错位、看起来残缺变形(之前的顺序正是"模型不完善/渲染不完善"的根因)。
        poseStack.scale(livingState.scale, livingState.scale, livingState.scale);
        if (livingState instanceof PhantomRenderState) {
            poseStack.mulPose(Axis.XP.rotationDegrees(livingState.xRot));
        }
        poseStack.scale(-1, -1, 1);
        if (livingState instanceof PhantomRenderState phantom) {
            float sizeScale = 1.0F + 0.15F * phantom.size;
            poseStack.scale(sizeScale, sizeScale, sizeScale);
            poseStack.translate(0.0F, 1.3125F, 0.1875F);
        }
        poseStack.translate(0, -1.501, 0);

        StatueFrames frames = state.blockEntity.getStatueFrames();
        int selIndex = -1;
        StatueFrameData selFd = null;
        if (frames != null && !frames.isEmpty()) {
            // 雕像内部存储帧:独立克隆模型(空 setupAnim)按当前帧回放,
            // 未右击过则显示最后一帧(转化前瞬间)。盔甲等图层通过共享渲染状态参数保持姿态一致。
            int total = frames.frames().size();
            selIndex = state.blockEntity.getCurrentFrame();
            if (selIndex < 0 || selIndex >= total) {
                selIndex = total - 1;
            }
            selFd = frames.frames().get(selIndex);
        }
        // 先按所选帧的形状参数(如河豚 puff 状态)解析正确的本体模型几何,再创建克隆,
        // 否则河豚这类按状态换模型的生物其实际几何会随实时渲染而错乱。
        EntityModel original = resolveOriginalModel(renderer, livingRenderer, selFd);
        Model renderModel = getOrCreateClone(state, original);
        if (frames != null && !frames.isEmpty()) {
            int index = selIndex;
            StatueFrameData fd = selFd;
            if (fd.extra() != null) {
                // 新格式:帧参数 → 渲染状态(供盔甲等共享图层姿态一致),再逐骨骼直接回放到克隆模型。
                if (frames != loggedFrames) {
                    loggedFrames = frames;
                    CopperEnchantMod.LOGGER.info("[Renderer] 使用逐骨骼回放渲染 pos={} 帧数={} 当前帧={} 部件数={}",
                        state.blockEntity.getBlockPos(), frames.frames().size(), index,
                        frames.paths() == null ? 0 : frames.paths().size());
                }
                StatuePoseCapture.applyFrameExtra(livingState, fd.extra());
                // 环境适配必须在 applyFrameExtra(写入捕获的水/陆参数)之后,以雕像实际环境为准覆盖
                applyStatueEnvironment(state.blockEntity, livingState, state.entityRenderState);
                // 小模型(isBaby)强制走 setupAnim 烘焙:逐骨骼旋转/位移与 baby 缩放叠加易错乱,
                // 烘焙用渲染参数(含 isBaby/scale)驱动更稳定。
                boolean hasBoneData = !livingState.isBaby && fd.rotations() != null && frames.paths() != null
                    && !frames.paths().isEmpty() && fd.rotations().length >= frames.paths().size() * 3;
                if (hasBoneData) {
                    // 完整逐骨骼回放:旋转/位移/可见性精确还原,手/脚/腿/头/躯干全覆盖。
                    // 若克隆模型与帧数据结构不匹配(小模型/变体),回退 setupAnim 烘焙,避免渲染错乱。
                    // 回退会复用共享的全局模型 original,其 setupAnim 遇极端帧参数可能抛错,
                    // 此处捕获异常以绝不让整尊雕像因单帧失败而透明。
                    if (!StatuePoseCapture.applyStoredPose(renderModel, frames, index)) {
                        try {
                            original.setupAnim(livingState);
                            StatuePoseCapture.copyPose(original.root(), renderModel.root());
                        } catch (Exception e) {
                            CopperEnchantMod.LOGGER.warn("[Renderer] setupAnim 回退失败 pos={} 帧={} err={}",
                                state.blockEntity.getBlockPos(), index, e.toString());
                        }
                    }
                } else {
                    // 旧帧无骨骼数据:回退 setupAnim 烘焙
                    try {
                        original.setupAnim(livingState);
                        StatuePoseCapture.copyPose(original.root(), renderModel.root());
                    } catch (Exception e) {
                        CopperEnchantMod.LOGGER.warn("[Renderer] setupAnim 全帧回退失败 pos={} 帧={} err={}",
                            state.blockEntity.getBlockPos(), index, e.toString());
                    }
                }
            } else {
                // 旧格式:直接按捕获旋转写入克隆模型
                if (frames != loggedFrames) {
                    loggedFrames = frames;
                    CopperEnchantMod.LOGGER.info("[Renderer] 使用旋转回放渲染 pos={} 帧数={} 当前帧={}",
                        state.blockEntity.getBlockPos(), frames.frames().size(), index);
                }
                StatuePoseCapture.applyStoredFrame(renderModel, frames, index);
                StatuePoseCapture.registerStoredFrameOverride(state.entityRenderState, frames, index);
            }
        } else {
            // 无存储帧(如旧存档/帧未同步):回退到服务端参数快照驱动的克隆模型
            applyStatueEnvironment(state.blockEntity, livingState, state.entityRenderState);
            original.setupAnim(livingState);
            StatuePoseCapture.copyPose(original.root(), renderModel.root());
        }

        // 可见性兜底:任何来源(克隆快照/逐帧回放/图层)都不得把部位设成隐藏,
        // 否则 ModelPart.render 直接跳过该子树导致雕像透明。此处绘前强制全身可见。
        StatuePoseCapture.setAllVisible(renderModel.root());

        // 注:水里或已由 applyFishOnLand 处理侧躺,此处无需额外变换。
        collector.submitModel(renderModel, state.entityRenderState, poseStack,
            renderModel.renderType(texture), state.lightCoords, OverlayTexture.NO_OVERLAY, -1, null, 0, null);

        List layers = ((LivingEntityRendererAccessor) (Object) livingRenderer).getLayers();
        for (Object obj : layers) {
            if (obj instanceof LivingEntityEmissiveLayer) continue;
            RenderLayer rawLayer = (RenderLayer) obj;
            rawLayer.submit(poseStack, collector, state.lightCoords, state.entityRenderState, 0.0f, 1.0f);
        }
        poseStack.popPose();
    }

    /**
     * 环境适配:按雕像方块当前所处环境覆盖渲染状态。
     * 鱼在水中的雕像 → 游泳姿态;陆上 → 扑腾/侧躺;白天露天的亡灵 → 燃烧(火焰粒子由服务端生成)。
     * @return 雕像当前是否处于水中
     */
    private boolean applyStatueEnvironment(CopperStatueBlockEntity be, LivingEntityRenderState livingState,
                                        EntityRenderState fullState) {
        boolean inWater = statueInWater(be);
        livingState.isInWater = inWater;
        if (livingState instanceof HumanoidRenderState h && inWater) {
            h.swimAmount = Math.max(h.swimAmount, 0.5f);
        }
        LivingEntity entity = be.getEntity();
        boolean undead = entity instanceof AbstractSkeleton || entity instanceof Zombie;
        boolean sunBurn = undead && !inWater && be.getLevel() != null
            && be.getLevel().getSkyDarken() < 4 && be.getLevel().canSeeSkyFromBelowWater(be.getBlockPos().above());
        fullState.displayFireAnimation = sunBurn;
        return inWater;
    }

    /**
     * 陆地上的鱼模型按原版侧躺贴地:与 Cod/Salmon/TropicalFish 的 setupRotations 一致,
     * 仅在水外时做微位移 + Z轴旋转90°。只调整模型朝向、不触碰方块整体位移,避免整块错位。
     * 河豚(pufferfish)原版就不做该翻转,保持原样。
     */
    private void applyFishOnLand(EntityRenderer renderer,
                                 LivingEntityRenderState state, PoseStack poseStack) {
        if (state.isInWater) return;
        if (renderer instanceof CodRenderer) {
            poseStack.translate(0.1f, 0.1f, -0.1f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
        } else if (renderer instanceof SalmonRenderer || renderer instanceof TropicalFishRenderer) {
            poseStack.translate(0.2f, 0.1f, 0.0f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
        }
    }

    /** 检测雕像方块本身与挨着的流体是否处于水中。 */
    private static boolean statueInWater(CopperStatueBlockEntity be) {
        Level level = be.getLevel();
        BlockPos pos = be.getBlockPos();
        boolean inWater = false;
        if (level != null) {
            BlockState bs = level.getBlockState(pos);
            if (bs.hasProperty(BlockStateProperties.WATERLOGGED)
                    && bs.getValue(BlockStateProperties.WATERLOGGED)) {
                inWater = true;
            }
            if (!inWater) {
                for (Direction d : Direction.values()) {
                    if (level.getFluidState(pos.relative(d)).is(Fluids.WATER)) {
                        inWater = true;
                        break;
                    }
                }
            }
        }
        return inWater;
    }

    /**
     * 解析雕像本体应使用的几何模型。
     * <p>绝大多数生物 getModel() 稳定;河豚渲染器会在 submit 里按 puff 状态实时切换
     * Big/Mid/Small 三套不同几何(连骨骼名都不同),getModel() 返回值因此不确定。
     * 此处按所选帧记录下的 puff 状态取对应模型,使冻结体积稳定。防御性:读取失败回退 getModel()。
     */
    private EntityModel resolveOriginalModel(EntityRenderer renderer, LivingEntityRenderer livingRenderer,
                                             StatueFrameData fd) {
        if (renderer instanceof PufferfishRenderer) {
            PufferfishRendererAccessor acc = (PufferfishRendererAccessor) (Object) renderer;
            int puff = 2;
            if (fd != null && fd.extra() != null && fd.extra().typeExtra() != null) {
                puff = fd.extra().typeExtra().getIntOr("puffer_puff", 2);
            }
            if (puff <= 0) return acc.getSmallModel();
            if (puff == 1) return acc.getMidModel();
            return acc.getBigModel();
        }
        return livingRenderer.getModel();
    }

    private StatueEntityModel getOrCreateClone(CopperStatueRenderState state, EntityModel original) {
        StatueEntityModel model = cloneModels.get(state.blockEntity);
        int partCount = original.root().getAllParts().size();
        if (model == null || model.partCount() != partCount) {
            model = new StatueEntityModel(StatueEntityModel.clonePart(original.root()), original.renderType());
            cloneModels.put(state.blockEntity, model);
        }
        return model;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    public static class CopperStatueRenderState extends BlockEntityRenderState {
        public CopperStatueBlockEntity blockEntity;
        public LivingEntity entity;
        public EntityRenderState entityRenderState;
    }
}
