package com.bba.allied.data;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.Identifier;

import java.io.*;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class datConfig {
    public static final String MOD_ID = "allied";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    static Path path = FabricLoader.getInstance().getConfigDir().resolve("allied").resolve("teams.dat");

    public static void InitialiseDatFolder() throws IOException {
        Files.createDirectories(path.getParent());
        NbtCompound root = CreateDefault();
        if (!Files.exists(path)) {
            NbtIo.write(root, path); // writes directly to file
            datManager.init(root);
            LOGGER.info("Loaded Default .Dat File...");
        } else {
            root = NbtIo.read(path); // reads directly from file
            datManager.init(root);
            LOGGER.info("Loaded existing .Dat File...");
        }
    }

    public static NbtCompound CreateDefault() {
        NbtCompound root = new NbtCompound();

        // Version
        root.putString("version", "1.0.0");

        // Teams
        NbtCompound teams = new NbtCompound();
        root.put("teams", teams);

        // Settings
        NbtCompound settings = new NbtCompound();
        root.put("settings", settings);

        settings.putInt("maxMembers", 5);
        settings.putBoolean("echest", true);

        return root;
    }

}

