package com.example.copper_enchant.registry;

import com.example.copper_enchant.CopperEnchantMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;

public class ModEnchantments {
    public static final ResourceKey<Enchantment> COPPERIZATION =
        ResourceKey.create(Registries.ENCHANTMENT, CopperEnchantMod.id("copperization"));

    public static void register() {
    }
}
