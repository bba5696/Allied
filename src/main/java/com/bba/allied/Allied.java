package com.bba.allied;

import com.bba.allied.commands.commands;
import com.bba.allied.commands.adminCommands;
import com.bba.allied.commands.teamCommands;
import com.bba.allied.storage.teamStorage;
import com.bba.allied.teamUtils.teamFeatures;
import com.bba.allied.data.datConfig;
import com.bba.allied.teamUtils.teamUtils;
import com.bba.allied.compat.placeholderCompat;
import com.bba.allied.data.datManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
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
        adminCommands.registerCommands();
        teamCommands.registerCommands();
        teamUtils.register();
        teamFeatures.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> teamStorage.tick());
        // DISCONNECT can fire on a network thread, so hand it to the server thread
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> teamStorage.onDisconnect(handler.player)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> teamStorage.closeAll());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                datManager.get().rememberName(handler.player.getUUID(), handler.player.getGameProfile().name());
            } catch (IOException e) {
                LOGGER.error("Failed to save player name", e);
            }
            runDelayed(server, () -> teamUtils.rebuildTeams(server), 3);
            datManager.get().sendLoginNotices(handler.player, server);
        });

        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            placeholderCompat.register();
            LOGGER.info("Placeholder API found, registered Allied placeholders");
        }

        LOGGER.info("Mod Successfully Initialized!");
    }
}