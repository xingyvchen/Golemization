package com.example.copper_enchant.data;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端待接收帧缓存:客户端在转化完成后把捕获的骨骼帧发来,
 * 暂存于此,直到对应的傀儡方块实体在保存/交互时消费。
 */
public final class StatueFrameCache {

    private static final long KEEP_MILLIS = 5 * 60_000L;
    private static final Map<UUID, StatueFrames> PENDING = new HashMap<>();
    private static final Map<UUID, Long> STAMP = new HashMap<>();
    private static long lastPrune = 0;

    private StatueFrameCache() {
    }

    public static void put(UUID uuid, StatueFrames frames) {
        if (uuid == null || frames == null || frames.isEmpty()) return;
        PENDING.put(uuid, frames);
        STAMP.put(uuid, System.currentTimeMillis());
    }

    public static StatueFrames consume(UUID uuid) {
        if (uuid == null) return null;
        StatueFrames frames = PENDING.remove(uuid);
        if (frames != null) {
            STAMP.remove(uuid);
        }
        return frames;
    }

    /** 傀儡方块销毁时主动清除其待消费帧,释放内存。 */
    public static void remove(UUID uuid) {
        if (uuid == null) return;
        PENDING.remove(uuid);
        STAMP.remove(uuid);
    }

    public static void prune() {
        long now = System.currentTimeMillis();
        if (now - lastPrune < 60_000L) return;
        lastPrune = now;
        Iterator<Map.Entry<UUID, Long>> it = STAMP.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now - entry.getValue() > KEEP_MILLIS) {
                PENDING.remove(entry.getKey());
                it.remove();
            }
        }
    }
}
