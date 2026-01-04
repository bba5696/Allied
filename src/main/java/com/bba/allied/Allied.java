package com.bba.allied;

import com.bba.allied.commands.commands;
import com.bba.allied.data.datConfig;
import com.bba.allied.teamUtils.teamUtils;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Allied implements ModInitializer {
	public static final String MOD_ID = "allied";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void runDelayed(MinecraftServer server, Runnable task, int ticks) {
        if (ticks <= 0) {
            server.execute(task);
        } else {
            server.execute(() -> runDelayed(server, task, ticks - 1));
        }
    }

    @Override
	public void onInitialize() {
		LOGGER.info("Initialising Allied Mod...");

        try {
            datConfig.InitialiseDatFolder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

		LOGGER.info("Allied Mod Data Loaded!");

        commands.registerCommands();
        teamUtils.register();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            runDelayed(server, () -> teamUtils.rebuildTeams(server), 3);
        });
    }
}