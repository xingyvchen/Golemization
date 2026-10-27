package com.example.copper_enchant.registry;

import com.example.copper_enchant.CopperEnchantMod;
import com.example.copper_enchant.block.CopperStatueBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class ModBlocks {

    public static CopperStatueBlock COPPER_STATUE;

    public static void register() {
        Identifier id = CopperEnchantMod.id("copper_statue");
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .setId(key)
            .strength(3.0f, 6.0f)
            .lightLevel(s -> 14)
            .noOcclusion()
            .requiresCorrectToolForDrops()
            .sound(net.minecraft.world.level.block.SoundType.COPPER);
        COPPER_STATUE = new CopperStatueBlock(properties);
        Registry.register(BuiltInRegistries.BLOCK, id, COPPER_STATUE);

        Registry.register(BuiltInRegistries.ITEM, id,
            new BlockItem(COPPER_STATUE, new Item.Properties().setId(
                ResourceKey.create(Registries.ITEM, id))));
    }
}
