package com.example.copper_enchant.network;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import com.example.copper_enchant.data.StatueFrameCache;
import com.example.copper_enchant.data.StatueFrames;
import com.example.copper_enchant.interaction.StatueFrameInteraction;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ModNetworking {

    private static final int MAX_FRAME_PACKET_BYTES = 512 * 1024;

    private ModNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().registerLarge(
            StatueFramesPayload.TYPE, StatueFramesPayload.CODEC, MAX_FRAME_PACKET_BYTES);
        PayloadTypeRegistry.serverboundPlay().register(
            StatueFrameSetPayload.TYPE, StatueFrameSetPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
            StatueFrameHoldPayload.TYPE, StatueFrameHoldPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            StatueFrameCursorPayload.TYPE, StatueFrameCursorPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
            StatueFrameDialogPayload.TYPE, StatueFrameDialogPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(StatueFramesPayload.TYPE, (payload, context) -> {
            context.player().level().getServer().execute(() -> {
                StatueFrames frames = payload.toFrames();
                CopperEnchantMod.LOGGER.info("[Net] 收到帧包 uuid={} 帧数={} 部件数={}",
                    payload.uuid(),
                    frames == null || frames.frames() == null ? 0 : frames.frames().size(),
                    frames == null || frames.paths() == null ? 0 : frames.paths().size());
                StatueFrameCache.put(payload.uuid(), frames);
                CopperStatueBlockEntity.deliverFrames(payload.uuid(), frames);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StatueFrameSetPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> {
                BlockPos pos = payload.pos();
                if (player.level().getBlockEntity(pos) instanceof CopperStatueBlockEntity be) {
                    be.setFrame(player.level(), player, payload.frame());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StatueFrameHoldPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> {
                StatueFrameInteraction.onHold(player.level(), player, payload.pos(), payload.down());
            });
        });
    }
}
