package com.example.copper_enchant.client;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.client.hud.StatueFrameHud;
import com.example.copper_enchant.client.player.CopperStatuePlayer;
import com.example.copper_enchant.client.renderer.CopperStatueBlockEntityRenderer;
import com.example.copper_enchant.client.screen.StatueFrameInputScreen;
import com.example.copper_enchant.client.util.StatueFrameHoldClient;
import com.example.copper_enchant.client.util.StatuePoseCapture;
import com.example.copper_enchant.network.StatueFrameCursorPayload;
import com.example.copper_enchant.network.StatueFrameDialogPayload;
import com.example.copper_enchant.registry.ModBlockEntities;
import com.example.copper_enchant.util.StatuePlayerFactory;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class CopperEnchantClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockEntityRendererRegistry.register(
            ModBlockEntities.COPPER_STATUE,
            CopperStatueBlockEntityRenderer::new
        );

        StatuePlayerFactory.set((level, profile) -> {
            if (level instanceof ClientLevel clientLevel) {
                return new CopperStatuePlayer(clientLevel, profile);
            }
            return null;
        });

        ClientPlayNetworking.registerGlobalReceiver(StatueFrameCursorPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                CopperEnchantMod.LOGGER.info("[Client] 收到帧光标包 {}/{}", payload.current(), payload.total());
                StatueFrameHud.show(payload.current(), payload.total());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(StatueFrameDialogPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                CopperEnchantMod.LOGGER.info("[Client] 收到跳帧输入框请求 pos={} 当前={} 总帧={}",
                    payload.pos(), payload.current(), payload.total());
                Minecraft.getInstance().setScreenAndShow(
                    new StatueFrameInputScreen(payload.pos(), payload.current(), payload.total()));
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StatueFrameHud.tick();
            StatuePoseCapture.tick();
            StatueFrameHoldClient.tick();
        });
    }
}
