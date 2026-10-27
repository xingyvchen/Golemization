package com.example.copper_enchant.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 客户端 → 服务端:输入框提交,把傀儡当前帧设为指定帧。 */
public record StatueFrameSetPayload(BlockPos pos, int frame) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatueFrameSetPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("copper_enchant", "statue_frame_set"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatueFrameSetPayload> CODEC = StreamCodec.of(
        StatueFrameSetPayload::write, StatueFrameSetPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, StatueFrameSetPayload p) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.frame());
    }

    private static StatueFrameSetPayload read(RegistryFriendlyByteBuf buf) {
        return new StatueFrameSetPayload(buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
