package com.example.copper_enchant.interaction;

import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import com.example.copper_enchant.network.StatueFrameDialogPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 木剑与傀儡的交互状态机:
 * <ul>
 *   <li>单击:前进一帧并锁定;</li>
 *   <li>快速双击(间隔 &lt;320ms 的连续两击,随后无第三击且未持续按住):弹出输入框,直接跳到指定帧;</li>
 *   <li>按住(客户端持续发送心跳):加速播放,间隔从 300ms 逐渐缩短到 50ms,即 1 秒播放 1 秒动画。</li>
 * </ul>
 */
public final class StatueFrameInteraction {

    private static final long DOUBLE_MS = 320;        // 连击窗口:两击间隔超过则视为两次独立单击
    private static final long DOUBLE_WAIT_MS = 250;   // 第二击后等待确认无第3击才弹输入框
    private static final long HOLD_TIMEOUT_MS = 1000;
    private static final long HOLD_START_DELAY_MS = 300;
    private static final long HOLD_MIN_DELAY_MS = 50;
    private static final long TICK_MS = 50;

    private record HoldState(UUID playerUuid, ServerLevel level, BlockPos pos,
                             long lastClick, int clickCount,
                             long delayMs, long elapsedMs, long lastHeartbeat) {

        HoldState clicked(long now) {
            return new HoldState(playerUuid, level, pos, now, clickCount + 1, delayMs, 0, lastHeartbeat);
        }

        HoldState heartbeat(long now) {
            return new HoldState(playerUuid, level, pos, lastClick, clickCount, delayMs, elapsedMs, now);
        }

        HoldState released() {
            return new HoldState(playerUuid, level, pos, lastClick, clickCount, delayMs, elapsedMs, 0);
        }

        HoldState advanceElapsed(long ms) {
            return new HoldState(playerUuid, level, pos, lastClick, clickCount, delayMs, elapsedMs + ms, lastHeartbeat);
        }

        HoldState withDelay(long newDelay) {
            return new HoldState(playerUuid, level, pos, lastClick, clickCount, newDelay, 0, lastHeartbeat);
        }

        boolean isHeld(long now) {
            return lastHeartbeat != 0 && now - lastHeartbeat < HOLD_TIMEOUT_MS;
        }
    }

    private static final Map<UUID, HoldState> HOLDS = new HashMap<>();

    private StatueFrameInteraction() {
    }

    /** 木剑右击(每次按下):前进一帧并播放音效;快速连续点击计入双击/按住计数。 */
    public static InteractionResult onWoodenSwordClick(ServerLevel level, BlockPos pos,
                                                       CopperStatueBlockEntity be, Player player,
                                                       InteractionHand hand) {
        long now = System.currentTimeMillis();
        UUID puid = player.getUUID();
        HoldState prev = HOLDS.get(puid);
        boolean fresh = prev == null || !prev.pos().equals(pos) || now - prev.lastClick() > DOUBLE_MS;
        if (fresh) {
            InteractionResult result = be.advanceOneFrame(level, player);
            if (result == InteractionResult.SUCCESS) {
                HOLDS.put(puid, new HoldState(puid, level, pos, now, 1, HOLD_START_DELAY_MS, 0, 0));
                level.playSound(null, pos, SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 0.6f, 1.6f);
                player.swing(hand);
            }
            return result;
        }
        be.advanceOneFrame(level, player);
        HOLDS.put(puid, prev.clicked(now));
        // 双击是否弹输入框由 serverTick 在确认无第3击、且非持续按住后统一判定,
        // 避免与按住加速播放互相干扰(见 serverTick)。
        return InteractionResult.SUCCESS;
    }

    /** 客户端心跳:down=true 按住(续期/新建会话),down=false 松开(保留计数供双击判定)。 */
    public static void onHold(ServerLevel level, ServerPlayer player, BlockPos pos, boolean down) {
        long now = System.currentTimeMillis();
        UUID puid = player.getUUID();
        if (down) {
            HoldState prev = HOLDS.get(puid);
            if (prev == null || !prev.pos().equals(pos)) {
                // 心跳先于 use 到达时以 0 计数新建,真实单击由 onWoodenSwordClick 负责 +1,
                // 避免同一击被误判为第二击
                HOLDS.put(puid, new HoldState(puid, level, pos, now, 0, HOLD_START_DELAY_MS, 0, now));
            } else {
                HOLDS.put(puid, prev.heartbeat(now));
            }
        } else {
            HoldState prev = HOLDS.get(puid);
            if (prev != null && prev.pos().equals(pos)) {
                HOLDS.put(puid, prev.released());
            }
        }
    }

    /** 服务端每 tick:按住期间加速推进帧;停止点击后按点击次数判定(恰 2 次→跳帧输入框)。 */
    public static void serverTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, HoldState>> it = HOLDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, HoldState> entry = it.next();
            HoldState st = entry.getValue();
            if (!st.level().getServer().equals(server)) continue;
            if (st.isHeld(now)) {
                HoldState next = st.advanceElapsed(TICK_MS);
                if (next.elapsedMs() >= next.delayMs()) {
                    next = next.withDelay(Math.max(HOLD_MIN_DELAY_MS, (long) (next.delayMs() * 0.8)));
                    if (st.level().getBlockEntity(st.pos()) instanceof CopperStatueBlockEntity be && be.hasFrames()) {
                        be.advanceOneFrame(st.level(), null);
                        ServerPlayer player = server.getPlayerList().getPlayer(st.playerUuid());
                        if (player != null) {
                            be.sendCursorTo(player);
                        }
                    }
                }
                HOLDS.put(entry.getKey(), next);
                continue;
            }
            if (now - st.lastClick() > DOUBLE_WAIT_MS && st.clickCount() == 2) {
                // 快速双击(恰两击且随后无第3击、非持续按住):弹出跳帧输入框
                if (st.level().getBlockEntity(st.pos()) instanceof CopperStatueBlockEntity be && be.hasFrames()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(st.playerUuid());
                    if (player != null) {
                        be.sendCursorTo(player);
                        ServerPlayNetworking.send(player,
                            new StatueFrameDialogPayload(st.pos(), be.getCurrentFrame() + 1, be.getTotalFrames()));
                    }
                }
                it.remove();
                continue;
            }
            if (now - st.lastClick() > DOUBLE_MS) {
                // 双击判定见上;这里清理超期状态,使之后单击重新以 clickCount=1 开始
                it.remove();
            }
        }
    }
}
