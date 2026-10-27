package com.example.copper_enchant.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/**
 * 木剑右击雕像后的帧进度提示,绘制在原版切换物品时物品名出现的位置
 * (物品栏上方居中):格式 "xxx/yyy |"(xxx 当前帧, yyy 总帧数)。
 */
public final class StatueFrameHud {

    private static final int SHOW_TICKS = 40;

    private static int current;
    private static int total;
    private static int timer;

    private StatueFrameHud() {
    }

    /** 由帧光标包接收器调用。 */
    public static void show(int currentFrame, int totalFrames) {
        current = currentFrame;
        total = totalFrames;
        timer = SHOW_TICKS;
    }

    public static void tick() {
        if (timer > 0) timer--;
    }

    /** 由 HudMixin 在 extractSelectedItemName 返回后调用,与原版物品名同一位置绘制。 */
    public static void render(GuiGraphicsExtractor extractor, Hud hud, boolean vanillaNameActive) {
        if (timer <= 0 || total <= 0) return;
        Component text = Component.literal(current + "/" + total + " |");
        Font font = hud.getFont();
        int width = font.width(text);
        int x = (extractor.guiWidth() - width) / 2;
        int y = extractor.guiHeight() - 59;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null && !minecraft.gameMode.canHurtPlayer()) {
            y += 14;
        }
        if (vanillaNameActive) {
            y += 10; // 原版物品名同时显示时错开一行,避免重叠
        }
        int alpha = Math.min(255, timer * 25);
        if (alpha <= 0) return;
        extractor.textWithBackdrop(font, text, x, y, width, ARGB.white(alpha));
    }
}
