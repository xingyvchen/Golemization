package com.example.copper_enchant.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * 傀儡内部的逐帧数据:paths 为旧格式部件路径表(所有帧共用,按捕获时 DFS 顺序),
 * 每帧 rotations 为 3 * paths.size() 个量化旋转(rad * 1000,short 打包为 byte[] 存储);
 * 新格式每帧附带 FrameExtra 渲染参数(14 字节/帧)与可选的类型特化 NBT。
 */
public record StatueFrames(List<String> paths, List<StatueFrameData> frames) {

    public boolean isEmpty() {
        return frames == null || frames.isEmpty();
    }

    public static void save(ValueOutput output, StatueFrames frames) {
        CompoundTag tag = new CompoundTag();
        ListTag pathList = new ListTag();
        for (String p : frames.paths()) {
            pathList.add(StringTag.valueOf(p));
        }
        tag.put("paths", pathList);
        int perFrame = frames.paths().size() * 3;
        byte[] packed = new byte[perFrame * frames.frames().size() * 2];
        byte[] packedTra = new byte[perFrame * frames.frames().size() * 2];
        byte[] packedVis = new byte[frames.paths().size() * frames.frames().size()];
        int i = 0, ti = 0, vi = 0;
        boolean hasRotations = false;
        boolean hasTra = false;
        for (StatueFrameData fd : frames.frames()) {
            short[] rots = fd.rotations();
            if (rots != null) {
                hasRotations = true;
                for (short s : rots) {
                    packed[i++] = (byte) (s >> 8);
                    packed[i++] = (byte) (s & 0xFF);
                }
            }
            short[] traArr = fd.tra();
            if (traArr != null) {
                hasTra = true;
                for (short s : traArr) {
                    packedTra[ti++] = (byte) (s >> 8);
                    packedTra[ti++] = (byte) (s & 0xFF);
                }
            }
            byte[] visArr = fd.vis();
            if (visArr != null) {
                System.arraycopy(visArr, 0, packedVis, vi, visArr.length);
            }
            vi += frames.paths().size();
        }
        tag.putByteArray("rot", hasRotations ? packed : new byte[0]);
        tag.putByteArray("tra", hasTra ? packedTra : new byte[0]);
        tag.putByteArray("vis", packedVis);

        boolean hasExtras = !frames.frames().isEmpty() && frames.frames().get(0).extra() != null;
        List<StatueFrameData.FrameExtra> extras = new ArrayList<>(frames.frames().size());
        ListTag typeList = new ListTag();
        for (StatueFrameData fd : frames.frames()) {
            extras.add(fd.extra());
            CompoundTag typeExtra = fd.extra() != null ? fd.extra().typeExtra() : null;
            typeList.add(typeExtra != null ? typeExtra : new CompoundTag());
        }
        if (hasExtras) {
            tag.putByteArray("extra", StatueFrameData.packExtras(extras));
            tag.putInt("extra_format", StatueFrameData.EXTRA_BYTES);
        } else {
            tag.putByteArray("extra", new byte[0]);
        }
        tag.put("type_extra", typeList);
        output.store("statue_frames", CompoundTag.CODEC, tag);
    }

    public static StatueFrames load(ValueInput input) {
        CompoundTag tag = input.read("statue_frames", CompoundTag.CODEC).orElse(null);
        if (tag == null) return null;
        ListTag pathList = tag.getListOrEmpty("paths");
        List<String> paths = new ArrayList<>(pathList.size());
        for (int i = 0; i < pathList.size(); i++) {
            paths.add(pathList.getStringOr(i, ""));
        }
        byte[] packed = tag.getByteArray("rot").orElse(null);
        byte[] packedTra = tag.getByteArray("tra").orElse(null);
        byte[] packedVis = tag.getByteArray("vis").orElse(null);
        byte[] extraBytes = tag.getByteArray("extra").orElse(null);
        ListTag typeList = tag.getListOrEmpty("type_extra");

        int perFrame = paths.size() * 3;
        int rotFrames = (packed == null || perFrame == 0) ? 0 : packed.length / (perFrame * 2);
        int traFrames = (packedTra == null || perFrame == 0) ? 0 : packedTra.length / (perFrame * 2);
        int visFrames = (packedVis == null || paths.isEmpty()) ? 0 : packedVis.length / paths.size();
        // 旧存档无 extra_format,默认 v1(14 字节/帧);新格式 23 字节/帧
        int extraFormat = tag.getIntOr("extra_format", StatueFrameData.EXTRA_BYTES_V1);
        int extraFrames = extraBytes == null ? 0 : extraBytes.length / extraFormat;
        int frameCount = Math.max(Math.max(rotFrames, traFrames), Math.max(visFrames, extraFrames));
        if (frameCount <= 0) return null;

        List<StatueFrameData.FrameExtra> parsedExtras =
            extraFrames > 0 ? StatueFrameData.unpackExtras(extraBytes, extraFormat) : List.of();

        List<StatueFrameData> frames = new ArrayList<>(frameCount);
        for (int f = 0; f < frameCount; f++) {
            short[] rotations = null;
            if (packed != null && perFrame > 0 && f < rotFrames) {
                rotations = new short[perFrame];
                for (int j = 0; j < perFrame; j++) {
                    int base = (f * perFrame + j) * 2;
                    rotations[j] = (short) (((packed[base] & 0xFF) << 8) | (packed[base + 1] & 0xFF));
                }
            }
            short[] traArr = null;
            if (packedTra != null && perFrame > 0 && f < traFrames) {
                traArr = new short[perFrame];
                for (int j = 0; j < perFrame; j++) {
                    int base = (f * perFrame + j) * 2;
                    traArr[j] = (short) (((packedTra[base] & 0xFF) << 8) | (packedTra[base + 1] & 0xFF));
                }
            }
            byte[] visArr = null;
            if (packedVis != null && !paths.isEmpty() && f < visFrames) {
                visArr = new byte[paths.size()];
                System.arraycopy(packedVis, f * paths.size(), visArr, 0, paths.size());
            }
            StatueFrameData.FrameExtra extra = null;
            if (f < parsedExtras.size()) {
                extra = parsedExtras.get(f);
                if (f < typeList.size()) {
                    Tag t = typeList.get(f);
                    if (t instanceof CompoundTag ct && !ct.isEmpty()) {
                        StatueFrameData.FrameExtra base = extra;
                        extra = new StatueFrameData.FrameExtra(base.ageInTicks(), base.walkPos(), base.walkSpeed(),
                            base.swimAmount(), base.attackTime(), base.poseOrdinal(), base.flags(), base.scale(),
                            base.deathTime(), base.useTicks(), base.xRot(), base.bodyRot(), base.yRot(),
                            base.rightArmPose(), base.leftArmPose(), base.mainArm(), base.attackArm(), ct);
                    }
                }
            }
            frames.add(new StatueFrameData(rotations, traArr, visArr, extra));
        }
        return new StatueFrames(paths, frames);
    }
}
