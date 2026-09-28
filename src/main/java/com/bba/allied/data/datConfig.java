package com.bba.allied.data;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.*;
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
        CompoundTag root = CreateDefault();
        if (!Files.exists(path)) {
            NbtIo.write(root, path);
            datManager.init(root);
            LOGGER.info("Loaded Default .Dat File...");
        } else {
            root = NbtIo.read(path);
            datManager.init(root);
            LOGGER.info("Loaded existing .Dat File...");
        }
    }

    public static CompoundTag CreateDefault() {
        CompoundTag root = new CompoundTag();

        root.putString("version", "1.0.0");

        CompoundTag teams = new CompoundTag();
        root.put("teams", teams);

        CompoundTag settings = new CompoundTag();
        root.put("settings", settings);

        settings.putInt("maxMembers", 5);
        settings.putBoolean("echest", true);
        ListTag blockTeamsSettings = new ListTag();
        settings.put("blockTeamsSettings", blockTeamsSettings);
        settings.putInt("maxTeamNameLength", 16);
        settings.putInt("maxTeamTagLength", 4);
        settings.putBoolean("exportJson", false);
        settings.putBoolean("storageDefault", false);

        return root;
    }
}

