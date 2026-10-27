package com.example.copper_enchant.client.mixin;

import com.example.copper_enchant.client.hud.StatueFrameHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版物品名(物品栏上方)的提取方法返回后,绘制傀儡帧进度提示。 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Shadow
    private int toolHighlightTimer;

    @Inject(method = "extractSelectedItemName", at = @At("RETURN"))
    private void copperEnchant$drawFrameHint(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        StatueFrameHud.render(extractor, (Hud) (Object) this, toolHighlightTimer > 0);
    }
}
