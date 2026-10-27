package com.example.copper_enchant.client.mixin;

import com.example.copper_enchant.client.util.StatuePoseCapture;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 延迟渲染管线在绘制阶段对每个模型节点执行 setupAnim 后再 renderToBuffer。
 * 雕像的姿态必须在这一时刻回放，否则提交时设置的骨骼旋转会被 setupAnim 覆盖。
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {

    @Inject(method = "prepareModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V",
        shift = At.Shift.AFTER))
    private void copperEnchant$applyStatuePose(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
        // 回放:雕像在绘制阶段应用存储姿态
        StatuePoseCapture.tryApplyDrawOverride(submit.model(), submit.state());
        // 捕获:记录的活体生物在 setupAnim 完成后逐骨骼快照(旋转/位移/可见性)
        StatuePoseCapture.recordBonePose(submit.state(), submit.model());
    }
}
