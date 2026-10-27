package com.example.copper_enchant;

import com.example.copper_enchant.registry.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopperEnchantMod implements ModInitializer {
    public static final String MOD_ID = "copper_enchant";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModEffects.register();
        ModEnchantmentEffects.register();
        ModParticles.register();
        ModBlocks.register();
        ModBlockEntities.register();
        ModEnchantments.register();
        com.example.copper_enchant.network.ModNetworking.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            com.example.copper_enchant.effect.CopperizationEffect.cleanupExpired(server);
            com.example.copper_enchant.data.StatueFrameCache.prune();
            com.example.copper_enchant.interaction.StatueFrameInteraction.serverTick(server);
            com.example.copper_enchant.blockentity.CopperStatueBlockEntity.tickEnvStatues(server);
        });

        LOGGER.info("Copper Enchant initialized");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
