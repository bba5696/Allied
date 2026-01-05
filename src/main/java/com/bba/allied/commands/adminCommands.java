package com.bba.allied.commands;

import com.bba.allied.data.datConfig;
import com.bba.allied.data.datManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class adminCommands {

    // --- Pending resets map ---
    private static final Map<UUID, Confirmation> pendingResets = new HashMap<>();

    public record Confirmation(String code, long expiryTime) {
    }

    // --- Generate random confirmation code ---
    private static String generateCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(rand.nextInt(chars.length())));
        }
        return sb.toString();
    }

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

                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("Team member cap set to " + newCap),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(CommandManager.literal("info")
                                .then(CommandManager.argument("teamName", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            datManager.get().getData().getCompoundOrEmpty("teams").getKeys()
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            MinecraftServer server = context.getSource().getServer();
                                            String teamName = StringArgumentType.getString(context, "teamName");

                                            Text info = datManager.get().getTeamInfo(server, teamName);

                                            context.getSource().sendFeedback(() -> info, false);
                                            return 1;
                                        })
                                )
                        )

                        .then(CommandManager.literal("list")
                                .executes(context -> {
                                    NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");

                                    if (teams.isEmpty()) {
                                        context.getSource().sendFeedback(
                                                () -> Text.literal("There are no teams on the server."),
                                                false
                                        );
                                        return 1;
                                    }

                                    context.getSource().sendFeedback(
                                            () -> Text.literal("Teams on the server:")
                                                    .formatted(Formatting.GOLD),
                                            false
                                    );

                                    for (String teamName : teams.getKeys()) {
                                        context.getSource().sendFeedback(
                                                () -> Text.literal("- ")
                                                        .formatted(Formatting.GRAY)
                                                        .append(Text.literal(teamName).formatted(Formatting.YELLOW)),
                                                false
                                        );
                                    }

                                    return 1;
                                })
                        )

                        .then(CommandManager.literal("reset")
                                // Optional code argument
                                .then(CommandManager.argument("code", StringArgumentType.string())
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            if (player == null) return 0;

                                            UUID uuid = player.getUuid();
                                            String enteredCode = StringArgumentType.getString(context, "code");
                                            Confirmation confirm = pendingResets.get(uuid);

                                            if (confirm == null || System.currentTimeMillis() > confirm.expiryTime) {
                                                context.getSource().sendError(Text.literal("You haven't started a reset or the code has expired!"));
                                                pendingResets.remove(uuid);
                                                return 0;
                                            }

                                            if (!confirm.code.equalsIgnoreCase(enteredCode)) {
                                                context.getSource().sendError(Text.literal("Incorrect code!"));
                                                return 0;
                                            }

                                            MinecraftServer server = context.getSource().getServer();

                                            try {
                                                datManager.get().resetData(server);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                            pendingResets.remove(uuid);

                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("All team data has been wiped!").formatted(Formatting.RED),
                                                    false
                                            );

                                            return 1;
                                        })
                                )
                                // No code → generate confirmation code
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    UUID uuid = player.getUuid();
                                    if (pendingResets.containsKey(uuid)) {
                                        context.getSource().sendError(Text.literal(
                                                "You already have a pending reset! Enter your existing code or wait until it expires."
                                        ));
                                        return 0;
                                    }

                                    String code = generateCode();
                                    long expiry = System.currentTimeMillis() + 60_000; // 60 seconds
                                    pendingResets.put(uuid, new Confirmation(code, expiry));

                                    context.getSource().sendFeedback(
                                            () -> Text.literal("⚠ Are you sure you want to continue? This will wipe all data!").formatted(Formatting.RED),
                                            false
                                    );

                                    context.getSource().sendFeedback(
                                            () -> Text.literal("Please enter the code to confirm: /alliedAdmin reset " + code).formatted(Formatting.YELLOW),
                                            false
                                    );

                                    return 1;
                                })
                        )


        ));
    }
}
