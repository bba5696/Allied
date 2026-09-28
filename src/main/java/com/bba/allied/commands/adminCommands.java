package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.bba.allied.teamUtils.teamUtils;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class adminCommands {

    private static final Map<UUID, Confirmation> pendingResets = new HashMap<>();

    public record Confirmation(String code, long expiryTime) {
    }

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
                Commands.literal("alliedAdmin")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("memberCap")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            int newCap = IntegerArgumentType.getInteger(context, "value");
                                            CompoundTag data = datManager.get().getData();
                                            CompoundTag settings = data.getCompoundOrEmpty("settings");
                                            settings.putInt("maxMembers", newCap);

                                            try {
                                                datManager.get().save();
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Team member cap set to " + newCap),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("maxTeamNameLength")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            int newLength = IntegerArgumentType.getInteger(context, "value");
                                            CompoundTag data = datManager.get().getData();
                                            CompoundTag settings = data.getCompoundOrEmpty("settings");
                                            settings.putInt("maxTeamNameLength", newLength);

                                            try {
                                                datManager.get().save();
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Team name max length set to " + newLength),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("maxTeamTagLength")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            int newLength = IntegerArgumentType.getInteger(context, "value");
                                            CompoundTag data = datManager.get().getData();
                                            CompoundTag settings = data.getCompoundOrEmpty("settings");
                                            settings.putInt("maxTeamTagLength", newLength);

                                            try {
                                                datManager.get().save();
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Team tag max length set to " + newLength),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("exportJson")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(context -> {
                                            boolean value = BoolArgumentType.getBool(context, "value");
                                            datManager.get().getData().getCompoundOrEmpty("settings").putBoolean("exportJson", value);

                                            try {
                                                datManager.get().save();
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal(value
                                                            ? "Teams will now be exported to config/allied/teams.json"
                                                            : "Stopped exporting teams.json"),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("info")
                                .then(Commands.argument("teamName", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            datManager.get().getData().getCompoundOrEmpty("teams").keySet()
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            MinecraftServer server = context.getSource().getServer();
                                            String teamName = StringArgumentType.getString(context, "teamName");

                                            Component info = datManager.get().getTeamInfo(server, teamName);

                                            context.getSource().sendSuccess(() -> info, false);
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("list")
                                .executes(context -> {
                                    CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");

                                    if (teams.isEmpty()) {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("There are no teams on the server."),
                                                false
                                        );
                                        return 1;
                                    }

                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Teams on the server:")
                                                    .withStyle(ChatFormatting.GOLD),
                                            false
                                    );

                                    for (String teamName : teams.keySet()) {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("- ")
                                                        .withStyle(ChatFormatting.GRAY)
                                                        .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW)),
                                                false
                                        );
                                    }

                                    return 1;
                                })
                        )

                        .then(Commands.literal("reset")
                                .then(Commands.argument("code", StringArgumentType.string())
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player == null) return 0;

                                            UUID uuid = player.getUUID();
                                            String enteredCode = StringArgumentType.getString(context, "code");
                                            Confirmation confirm = pendingResets.get(uuid);

                                            if (confirm == null || System.currentTimeMillis() > confirm.expiryTime) {
                                                context.getSource().sendFailure(Component.literal("You haven't started a reset or the code has expired!"));
                                                pendingResets.remove(uuid);
                                                return 0;
                                            }

                                            if (!confirm.code.equalsIgnoreCase(enteredCode)) {
                                                context.getSource().sendFailure(Component.literal("Incorrect code!"));
                                                return 0;
                                            }

                                            MinecraftServer server = context.getSource().getServer();

                                            try {
                                                datManager.get().resetData(server);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                            pendingResets.remove(uuid);

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("All team data has been wiped!").withStyle(ChatFormatting.RED),
                                                    false
                                            );

                                            return 1;
                                        })
                                )
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    UUID uuid = player.getUUID();
                                    Confirmation existing = pendingResets.get(uuid);
                                    if (existing != null && System.currentTimeMillis() <= existing.expiryTime()) {
                                        context.getSource().sendFailure(Component.literal(
                                                "You already have a pending reset! Enter your existing code or wait until it expires."
                                        ));
                                        return 0;
                                    }

                                    String code = generateCode();
                                    long expiry = System.currentTimeMillis() + 60_000;
                                    pendingResets.put(uuid, new Confirmation(code, expiry));

                                    context.getSource().sendSuccess(
                                            () -> Component.literal("⚠ Are you sure you want to continue? This will wipe all data!").withStyle(ChatFormatting.RED),
                                            false
                                    );

                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Please enter the code to confirm: /alliedAdmin reset " + code).withStyle(ChatFormatting.YELLOW),
                                            false
                                    );

                                    return 1;
                                })
                        )

                        .then(Commands.literal("blockSettings")
                                .then(Commands.argument("teamName", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            datManager.get().getData()
                                                    .getCompoundOrEmpty("teams")
                                                    .keySet()
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    String teamName = StringArgumentType.getString(context, "teamName");
                                                    boolean value = BoolArgumentType.getBool(context, "value");

                                                    CompoundTag data = datManager.get().getData();
                                                    CompoundTag teams = data.getCompoundOrEmpty("teams");

                                                    if (!teams.contains(teamName)) {
                                                        context.getSource().sendFailure(
                                                                Component.literal("Team '" + teamName + "' does not exist!")
                                                        );
                                                        return 0;
                                                    }

                                                    CompoundTag settings = data.getCompoundOrEmpty("settings");
                                                    ListTag blocked = settings.getListOrEmpty("blockTeamsSettings");

                                                    int index = -1;
                                                    for (int i = 0; i < blocked.size(); i++) {
                                                        if (teamName.equalsIgnoreCase(blocked.getString(i).orElse(""))) {
                                                            index = i;
                                                            break;
                                                        }
                                                    }

                                                    if (value) {
                                                        if (index != -1) {
                                                            context.getSource().sendFailure(
                                                                    Component.literal("This team is already blocked!")
                                                            );
                                                            return 0;
                                                        }

                                                        blocked.add(StringTag.valueOf(teamName));
                                                        context.getSource().sendSuccess(
                                                                () -> Component.literal("Team '" + teamName + "' is now blocked from changing settings."),
                                                                false
                                                        );
                                                    } else {
                                                        if (index == -1) {
                                                            context.getSource().sendFailure(
                                                                    Component.literal("This team is not blocked already!")
                                                            );
                                                            return 0;
                                                        }

                                                        blocked.remove(index);
                                                        context.getSource().sendSuccess(
                                                                () -> Component.literal("Team '" + teamName + "' can now change settings."),
                                                                false
                                                        );
                                                    }

                                                    settings.put("blockTeamsSettings", blocked);

                                                    try {
                                                        datManager.get().save();
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }

                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("modifySettings")
                                .then(Commands.argument("teamName", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            datManager.get().getData().getCompoundOrEmpty("teams").keySet()
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String teamName = StringArgumentType.getString(context, "teamName");

                                            try {
                                                datManager.get().handleSettingsAdmin(context.getSource(), teamName, null, null);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }

                                            return 1;
                                        })
                                        .then(Commands.argument("setting", StringArgumentType.string())
                                                .suggests((context, builder) -> {
                                                    String teamName = StringArgumentType.getString(context, "teamName");
                                                    CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
                                                    CompoundTag teamData = teams.getCompoundOrEmpty(teamName);

                                                    CompoundTag settings = teamData.getCompoundOrEmpty("settings");
                                                    for (String key : settings.keySet()) builder.suggest(key);

                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> {
                                                    String teamName = StringArgumentType.getString(context, "teamName");
                                                    String setting = StringArgumentType.getString(context, "setting");

                                                    try {
                                                        datManager.get().handleSettingsAdmin(context.getSource(), teamName, setting, null);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }

                                                    return 1;
                                                })
                                                .then(Commands.argument("value", BoolArgumentType.bool())
                                                        .executes(context -> {
                                                            String teamName = StringArgumentType.getString(context, "teamName");
                                                            String setting = StringArgumentType.getString(context, "setting");
                                                            boolean value = BoolArgumentType.getBool(context, "value");

                                                            try {
                                                                datManager.get().handleSettingsAdmin(context.getSource(), teamName, setting, value);
                                                            } catch (IOException e) {
                                                                throw new RuntimeException(e);
                                                            }

                                                            teamUtils.rebuildTeams(context.getSource().getServer());

                                                            context.getSource().sendSuccess(
                                                                    () -> Component.literal("Admin set '" + setting + "' for team '" + teamName + "' to " + value),
                                                                    false
                                                            );
                                                            return 1;
                                                        })
                                                )
                                        )
                                )
                        )


        ));
    }
}
