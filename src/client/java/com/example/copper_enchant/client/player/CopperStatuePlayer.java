package com.example.copper_enchant.client.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.function.Supplier;

public class CopperStatuePlayer extends AbstractClientPlayer {

    private final Supplier<PlayerSkin> skinLookup;

    public CopperStatuePlayer(ClientLevel level, GameProfile profile) {
        super(level, profile);
        this.skinLookup = Minecraft.getInstance().getSkinManager().createLookup(profile, false);
    }

    @Override
    public PlayerSkin getSkin() {
        PlayerInfo info = getPlayerInfo();
        if (info != null) {
            return info.getSkin();
        }
        return this.skinLookup.get();
    }
}
