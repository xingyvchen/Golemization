package com.example.copper_enchant.client.mixin;

import com.example.copper_enchant.client.CopperRenderData;
import com.example.copper_enchant.client.util.StatuePoseCapture;
import com.example.copper_enchant.effect.CopperizationEffect;
import com.example.copper_enchant.registry.ModEffects;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void copperEnchant$onExtractRenderState(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (entity.hasEffect(ModEffects.COPPERIZATION)) {
            int elapsed = CopperizationEffect.getTickProgress(entity);
            float prog = Math.max(0f, Math.min(1f, elapsed / 60.0f));
            CopperRenderData.set(true, prog, entity.getBbHeight());
        } else {
            CopperRenderData.set(false, 0f, 1f);
        }
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void copperEnchant$onExtractRenderStateReturn(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        StatuePoseCapture.onExtract(entity, state);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void copperEnchant$onSubmitReturn(LivingEntityRenderState state, PoseStack poseStack,
                                              SubmitNodeCollector collector, CameraRenderState cameraState,
                                              CallbackInfo ci) {
        StatuePoseCapture.onSubmitted(state, ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel());
    }

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void copperEnchant$onGetModelTint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (CopperRenderData.hasEffect()) {
            int original = cir.getReturnValue();
            float prog = CopperRenderData.getProgress();
            int copperR = 0xCC;
            int copperG = 0x84;
            int copperB = 0x00;
            int origR = (original >> 16) & 0xFF;
            int origG = (original >> 8) & 0xFF;
            int origB = original & 0xFF;
            int r = (int) (origR + (copperR - origR) * prog);
            int g = (int) (origG + (copperG - origG) * prog);
            int b = (int) (origB + (copperB - origB) * prog);
            cir.setReturnValue((r << 16) | (g << 8) | b);
        }
    }
}
