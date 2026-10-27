package com.example.copper_enchant.registry;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.effect.CopperizationEffect;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;

public class ModEffects {
    public static final Holder<MobEffect> COPPERIZATION = Registry.registerForHolder(
        BuiltInRegistries.MOB_EFFECT,
        CopperEnchantMod.id("copperization"),
        new CopperizationEffect()
    );

    public static void register() {
    }
}
