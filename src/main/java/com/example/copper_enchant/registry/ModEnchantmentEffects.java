package com.example.copper_enchant.registry;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.enchantment.CopperizationEnchantmentEffect;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect;

public class ModEnchantmentEffects {
    public static final MapCodec<CopperizationEnchantmentEffect> COPPERIZATION_EFFECT =
        Registry.register(
            BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE,
            CopperEnchantMod.id("copperization_effect"),
            CopperizationEnchantmentEffect.CODEC
        );

    public static void register() {
    }
}
