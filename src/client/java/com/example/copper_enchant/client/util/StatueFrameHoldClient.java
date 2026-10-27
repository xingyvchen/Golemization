package com.example.copper_enchant.client.util;

import com.example.copper_enchant.block.CopperStatueBlock;
import com.example.copper_enchant.network.StatueFrameHoldPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 检测玩家按住右键指向雕像:按住期间每 5 tick(250ms)向服务端发送一次心跳,
 * 服务端据此加速播放动画(间隔 300ms 递减到 50ms,即 20fps,1 秒播放 1 秒动画);
 * 松开或移开准星时发送松开包。
 */
public final class StatueFrameHoldClient {

    private static final int HEARTBEAT_TICKS = 5;

    private static BlockPos activePos;
    private static boolean wasDown;
    private static int counter;

    private StatueFrameHoldClient() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            stop(mc);
            return;
        }
        boolean down = mc.options.keyUse.isDown();
        BlockPos pos = null;
        if (down && mc.hitResult instanceof BlockHitResult bhr
                && mc.level.getBlockState(bhr.getBlockPos()).getBlock() instanceof CopperStatueBlock) {
            pos = bhr.getBlockPos();
        }
        if (down && pos != null) {
            if (!wasDown || !pos.equals(activePos)) {
                activePos = pos;
                counter = 0;
            }
            wasDown = true;
            counter++;
            if (counter >= HEARTBEAT_TICKS) {
                counter = 0;
                ClientPlayNetworking.send(new StatueFrameHoldPayload(pos, true));
            }
        } else {
            if (wasDown && activePos != null) {
                ClientPlayNetworking.send(new StatueFrameHoldPayload(activePos, false));
            }
            wasDown = false;
            activePos = null;
            counter = 0;
        }
    }

    private static void stop(Minecraft mc) {
        if (wasDown && activePos != null) {
            ClientPlayNetworking.send(new StatueFrameHoldPayload(activePos, false));
        }
        wasDown = false;
        activePos = null;
        counter = 0;
    }
}
