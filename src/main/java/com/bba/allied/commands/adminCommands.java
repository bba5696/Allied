package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;

public class adminCommands {
    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
        CommandManager.literal("alliedAdmin")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .then(CommandManager.literal("memberCap")
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    int newCap = IntegerArgumentType.getInteger(context, "value");
                                    NbtCompound data = datManager.get().getData();
                                    NbtCompound settings = data.getCompoundOrEmpty("settings");

                                    settings.putInt("maxMembers", newCap);

                                    try {
                                        datManager.get().save();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }

                                    context.getSource().sendFeedback(() -> Text.literal("Team member cap set to " + newCap), false);
                                    return 1;
                                })
                        )
                )));
    }
}
