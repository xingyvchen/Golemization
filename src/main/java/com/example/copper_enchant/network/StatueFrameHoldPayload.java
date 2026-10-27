package com.example.copper_enchant.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 客户端 → 服务端:按住木剑右击雕像时的心跳(down=true 按住/续期,down=false 松开)。 */
public record StatueFrameHoldPayload(BlockPos pos, boolean down) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatueFrameHoldPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("copper_enchant", "statue_frame_hold"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatueFrameHoldPayload> CODEC = StreamCodec.of(
        StatueFrameHoldPayload::write, StatueFrameHoldPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, StatueFrameHoldPayload p) {
        buf.writeBlockPos(p.pos());
        buf.writeBoolean(p.down());
    }

    private static StatueFrameHoldPayload read(RegistryFriendlyByteBuf buf) {
        return new StatueFrameHoldPayload(buf.readBlockPos(), buf.readBoolean());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
