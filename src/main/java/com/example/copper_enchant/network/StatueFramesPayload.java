package com.example.copper_enchant.network;

import com.example.copper_enchant.data.StatueFrameData;
import com.example.copper_enchant.data.StatueFrames;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端 → 服务端:转化完成时上报该生物的逐帧动画。
 * 携带每帧骨骼数据(rotations 旋转 / tra 位移 / vis 可见性,均按 paths 对齐)与渲染参数(extras)。
 * paths/frames 为兼容保留;v2 起新增 traFrames/visFrames 通道。
 */
public record StatueFramesPayload(UUID uuid, List<String> paths, List<short[]> frames,
                                  List<short[]> traFrames, List<byte[]> visFrames,
                                  byte[] extras, List<CompoundTag> typeExtras)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatueFramesPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("copper_enchant", "statue_frames"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatueFramesPayload> CODEC = StreamCodec.of(
        StatueFramesPayload::write, StatueFramesPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, StatueFramesPayload payload) {
        buf.writeUUID(payload.uuid());
        buf.writeCollection(payload.paths(), (b, s) -> b.writeUtf(s));
        buf.writeCollection(payload.frames(), (b, arr) -> {
            short[] a = arr == null ? new short[0] : arr;
            b.writeVarInt(a.length);
            for (short s : a) {
                b.writeShort(s);
            }
        });
        buf.writeCollection(payload.traFrames(), (b, arr) -> {
            short[] a = arr == null ? new short[0] : arr;
            b.writeVarInt(a.length);
            for (short s : a) {
                b.writeShort(s);
            }
        });
        buf.writeCollection(payload.visFrames(), (b, arr) ->
            b.writeByteArray(arr == null ? new byte[0] : arr));
        buf.writeByteArray(payload.extras());
        buf.writeCollection(payload.typeExtras(), (b, tag) -> b.writeNbt(tag));
    }

    private static StatueFramesPayload read(RegistryFriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        List<String> paths = buf.readList(b -> b.readUtf());
        List<short[]> frames = buf.readList(b -> {
            int n = b.readVarInt();
            short[] arr = new short[n];
            for (int i = 0; i < n; i++) {
                arr[i] = b.readShort();
            }
            return arr;
        });
        List<short[]> traFrames = buf.readList(b -> {
            int n = b.readVarInt();
            short[] arr = new short[n];
            for (int i = 0; i < n; i++) {
                arr[i] = b.readShort();
            }
            return arr;
        });
        List<byte[]> visFrames = buf.readList(b -> b.readByteArray());
        byte[] extras = buf.readByteArray();
        List<CompoundTag> typeExtras = buf.readList(b -> b.readNbt());
        return new StatueFramesPayload(uuid, paths, frames, traFrames, visFrames, extras, typeExtras);
    }

    public StatueFrames toFrames() {
        List<StatueFrameData.FrameExtra> parsedExtras = StatueFrameData.unpackExtras(extras);
        int count = Math.max(Math.max(frames.size(), traFrames.size()),
            Math.max(visFrames.size(), parsedExtras.size()));
        List<StatueFrameData> data = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            short[] rots = i < frames.size() ? frames.get(i) : null;
            short[] traArr = i < traFrames.size() ? traFrames.get(i) : null;
            byte[] visArr = i < visFrames.size() ? visFrames.get(i) : null;
            StatueFrameData.FrameExtra extra = i < parsedExtras.size() ? parsedExtras.get(i) : null;
            if (extra != null && i < typeExtras.size()) {
                CompoundTag t = typeExtras.get(i);
                if (t != null && !t.isEmpty()) {
                    extra = new StatueFrameData.FrameExtra(extra.ageInTicks(), extra.walkPos(), extra.walkSpeed(),
                        extra.swimAmount(), extra.attackTime(), extra.poseOrdinal(), extra.flags(), extra.scale(),
                        extra.deathTime(), extra.useTicks(), extra.xRot(), extra.bodyRot(), extra.yRot(),
                        extra.rightArmPose(), extra.leftArmPose(), extra.mainArm(), extra.attackArm(), t);
                }
            }
            data.add(new StatueFrameData(rots, traArr, visArr, extra));
        }
        return new StatueFrames(paths, data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
