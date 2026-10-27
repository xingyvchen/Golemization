package com.example.copper_enchant.client.screen;

import com.example.copper_enchant.network.StatueFrameSetPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import org.lwjgl.glfw.GLFW;

/** 双击木剑弹出的跳帧输入框:输入 1..total 内的帧号,回车/确定后跳转。 */
public class StatueFrameInputScreen extends Screen {

    private final BlockPos pos;
    private final int total;
    private final int currentField;
    private EditBox editBox;

    public StatueFrameInputScreen(BlockPos pos, int current, int total) {
        super(Component.literal("跳转帧"));
        this.pos = pos;
        this.total = Math.max(1, total);
        this.currentField = Math.max(1, Math.min(current, this.total));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        this.editBox = new EditBox(this.font, cx - 70, cy - 25, 140, 20, Component.literal("帧号"));
        this.editBox.setMaxLength(6);
        this.editBox.setValue(String.valueOf(currentField));
        this.editBox.setResponder(s -> {
            String digits = s.replaceAll("[^0-9]", "");
            if (!digits.equals(s)) {
                this.editBox.setValue(digits);
            }
        });
        this.editBox.setFocused(true);
        this.addRenderableWidget(this.editBox);
        this.addRenderableWidget(Button.builder(Component.literal("确定"), b -> submit())
            .bounds(cx - 60, cy + 5, 55, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
            .bounds(cx + 5, cy + 5, 55, 20).build());
        this.setInitialFocus(this.editBox);
    }

    private void submit() {
        int frame;
        try {
            frame = Integer.parseInt(editBox.getValue().trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (frame < 1) {
            frame = 1;
        }
        if (frame > total) {
            frame = total;
        }
        ClientPlayNetworking.send(new StatueFrameSetPayload(pos, frame));
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        Component hint = Component.literal("输入目标帧 (1 - " + total + "),当前 " + currentField);
        int w = this.font.width(hint);
        extractor.textWithBackdrop(font, hint, (extractor.guiWidth() - w) / 2, this.height / 2 - 55, w, ARGB.white(255));
    }
}
