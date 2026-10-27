package com.example.copper_enchant.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 服务端 → 客户端:双击木剑后,让客户端弹出跳帧输入框。 */
public record StatueFrameDialogPayload(BlockPos pos, int current, int total)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatueFrameDialogPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("copper_enchant", "statue_frame_dialog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatueFrameDialogPayload> CODEC = StreamCodec.of(
        StatueFrameDialogPayload::write, StatueFrameDialogPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, StatueFrameDialogPayload p) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.current());
        buf.writeVarInt(p.total());
    }

    private static StatueFrameDialogPayload read(RegistryFriendlyByteBuf buf) {
        return new StatueFrameDialogPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
