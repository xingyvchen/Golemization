package com.example.copper_enchant.client.mixin;

import com.example.copper_enchant.client.util.StatuePoseCapture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 每帧渲染开始前清空雕像绘制时姿态覆盖注册表，防止跨帧残留。 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void copperEnchant$beginFrame(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                          boolean renderBlockOutline, CameraRenderState cameraState,
                                          Matrix4fc frustumMatrix, GpuBufferSlice gpuBufferSlice,
                                          Vector4f clearColor, boolean drawBlockOutline, CallbackInfo ci) {
        StatuePoseCapture.beginFrame();
    }
}
