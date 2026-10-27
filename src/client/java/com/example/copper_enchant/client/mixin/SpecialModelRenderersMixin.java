package com.example.copper_enchant.client.mixin;

import com.example.copper_enchant.client.renderer.CopperStatueItemSpecialRenderer;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpecialModelRenderers.class)
public class SpecialModelRenderersMixin {

    @Shadow
    @Final
    private static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> ID_MAPPER;

    @Inject(method = "bootstrap", at = @At("RETURN"))
    private static void copperEnchant$registerStatueRenderer(CallbackInfo ci) {
        ID_MAPPER.put(
            Identifier.fromNamespaceAndPath("copper_enchant", "copper_statue"),
            CopperStatueItemSpecialRenderer.Unbaked.MAP_CODEC
        );
    }
}
