package com.example.copper_enchant.registry;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

public class ModBlockEntities {
    public static final BlockEntityType<CopperStatueBlockEntity> COPPER_STATUE =
        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            CopperEnchantMod.id("copper_statue"),
            new BlockEntityType<>(CopperStatueBlockEntity::new, Set.of(
                ModBlocks.COPPER_STATUE
            ))
        );

    public static void register() {
    }
}
