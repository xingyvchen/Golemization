package com.example.copper_enchant.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.function.BiFunction;

public final class StatuePlayerFactory {
    private static BiFunction<Level, GameProfile, LivingEntity> factory;

    private StatuePlayerFactory() {
    }

    public static void set(BiFunction<Level, GameProfile, LivingEntity> playerFactory) {
        factory = playerFactory;
    }

    public static LivingEntity create(Level level, GameProfile profile) {
        if (factory == null) return null;
        try {
            return factory.apply(level, profile);
        } catch (Exception ignored) {
            return null;
        }
    }
}
