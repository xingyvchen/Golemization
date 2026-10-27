package com.example.copper_enchant.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 服务端 → 客户端:木剑点击后的当前帧提示(current/total)。 */
public record StatueFrameCursorPayload(int current, int total) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatueFrameCursorPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("copper_enchant", "statue_frame_cursor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatueFrameCursorPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarInt(payload.current());
            buf.writeVarInt(payload.total());
        },
        buf -> new StatueFrameCursorPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
