package com.bba.allied;

import com.bba.allied.commands.commands;
import com.bba.allied.data.datConfig;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Allied implements ModInitializer {
	public static final String MOD_ID = "allied";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);



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
    }
}