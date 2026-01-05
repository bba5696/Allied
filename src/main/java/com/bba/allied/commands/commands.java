package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.bba.allied.teamUtils.teamUtils;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("ALL")
public class commands {
    public static final String MOD_ID = "allied";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("allied")

                        .then(CommandManager.literal("create")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("tag", StringArgumentType.string())
                                                .executes(context -> {
                                                    String teamName = StringArgumentType.getString(context, "name");
                                                    String teamTag = StringArgumentType.getString(context, "tag");
                                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                                    assert player != null;
                                                    UUID ownerUuid = player.getUuid();

                                                    ServerCommandSource source = context.getSource();
                                                    MinecraftServer server = source.getServer();

                                                    datManager.get().addTeam(teamName, teamTag, ownerUuid);

                                                    teamUtils.rebuildTeams(server);

                                                    context.getSource().sendFeedback(
                                                            () -> Text.of("Successfully created team " + teamName),
                                                            false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(CommandManager.literal("disband")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    assert player != null;
                                    UUID ownerUuid = player.getUuid();
                                    try {
                                        datManager.get().removeTeam(ownerUuid);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }

                                    ServerCommandSource source = context.getSource();
                                    MinecraftServer server = source.getServer();

                                    teamUtils.rebuildTeams(server);

                                    context.getSource().sendFeedback(
                                            () -> Text.of("Successfully Disbanded team"),
                                            false
                                    );
                                    return 1;
                                })
                        )

                        .then(CommandManager.literal("leave")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    assert player != null;
                                    UUID ownerUuid = player.getUuid();
                                    try {
                                        datManager.get().leaveTeam(ownerUuid);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }

                                    ServerCommandSource source = context.getSource();
                                    MinecraftServer server = source.getServer();

                                    teamUtils.rebuildTeams(server);

                                    context.getSource().sendFeedback(
                                            () -> Text.of("Successfully left team"),
                                            false
                                    );
                                    return 1;
                                })
                        )

                        .then(CommandManager.literal("join")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                    .executes(context -> {
                                        String teamName = StringArgumentType.getString(context, "name");
                                        ServerPlayerEntity player = context.getSource().getPlayer();
                                        assert player != null;
                                        UUID ownerUuid = player.getUuid();

                                        ServerCommandSource source = context.getSource();
                                        MinecraftServer server = source.getServer();

                                        datManager.get().sendRequest(teamName, ownerUuid, server);

                                        context.getSource().sendFeedback(
                                                () -> Text.of("Sent Request to " + teamName),
                                                false
                                        );
                                        return 1;
                                    })
                                )
                        )

                        .then(CommandManager.literal("accept")
                                .then(CommandManager.argument("playerName", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            context.getSource().getServer().getPlayerManager().getPlayerList().forEach(player -> builder.suggest(player.getGameProfile().name()));
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayerEntity owner = context.getSource().getPlayer();
                                            assert owner != null;
                                            UUID ownerUUID = owner.getUuid();

                                            String targetName = StringArgumentType.getString(context, "playerName");
                                            UUID targetUUID;

                                            try {
                                                targetUUID = UUID.fromString(targetName);
                                            } catch (IllegalArgumentException e) {
                                                ServerPlayerEntity targetPlayer = context.getSource().getServer()
                                                        .getPlayerManager()
                                                        .getPlayer(targetName);

                                                if (targetPlayer == null) {
                                                    context.getSource().sendError(Text.literal("Player not found or not online!"));
                                                    return 0;
                                                }

                                                targetUUID = targetPlayer.getUuid();
                                            }

                                            try {
                                                datManager.get().handleRequest(ownerUUID, targetUUID, true);
                                            } catch (CommandSyntaxException e) {
                                                context.getSource().sendError((Text) e.getRawMessage());
                                                return 0;
                                            } catch (IOException e) {
                                                context.getSource().sendError(Text.literal("An internal error occurred while saving the team data."));
                                                e.printStackTrace();
                                                return 0;
                                            }

                                            ServerCommandSource source = context.getSource();
                                            MinecraftServer server = source.getServer();
                                            teamUtils.rebuildTeams(server);

                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("Accepted join request from " + targetName),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(CommandManager.literal("deny")
                                .then(CommandManager.argument("playerName", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            context.getSource().getServer().getPlayerManager().getPlayerList().forEach(player -> builder.suggest(player.getGameProfile().name()));
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayerEntity owner = context.getSource().getPlayer();
                                            assert owner != null;
                                            UUID ownerUUID = owner.getUuid();

                                            String targetName = StringArgumentType.getString(context, "playerName");
                                            ServerPlayerEntity targetPlayer = context.getSource().getServer()
                                                    .getPlayerManager().getPlayer(targetName);

                                            if (targetPlayer == null) {
                                                context.getSource().sendError(Text.literal("Player not found or not online!"));
                                                return 0;
                                            }

                                            UUID targetUUID = targetPlayer.getUuid();

                                            try {
                                                datManager.get().handleRequest(ownerUUID, targetUUID, false);
                                            } catch (IOException | CommandSyntaxException e) {
                                                throw new RuntimeException(e);
                                            }

                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("Denied join request from " + targetName),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(CommandManager.literal("info")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    String playerUuid = player.getUuid().toString();
                                    NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");

                                    NbtCompound playerTeam = null;
                                    String teamName = null;

                                    for (String key : teams.getKeys()) {
                                        NbtCompound team = teams.getCompoundOrEmpty(key);

                                        if (team.getString("owner").orElse("").equals(playerUuid)) {
                                            playerTeam = team;
                                            teamName = key;
                                            break;
                                        }

                                        var members = team.getListOrEmpty("members");
                                        for (int i = 0; i < members.size(); i++) {
                                            if (members.getString(i).orElse("").equals(playerUuid)) {
                                                playerTeam = team;
                                                teamName = key;
                                                break;
                                            }
                                        }

                                        if (playerTeam != null) break;
                                    }

                                    if (playerTeam == null) {
                                        context.getSource().sendFeedback(() -> Text.literal("You are not in a team!"), false);
                                        return 0;
                                    }

                                    String teamTag = playerTeam.getString("teamTag").orElse(teamName);
                                    String ownerUuid = playerTeam.getString("owner").orElse("");
                                    String ownerName = "Unknown";

                                    ServerPlayerEntity owner = null;
                                    try {
                                        if (player.getEntityWorld() instanceof ServerWorld serverWorld) {
                                            MinecraftServer server = serverWorld.getServer();
                                            owner = server.getPlayerManager().getPlayer(UUID.fromString(ownerUuid));
                                        }
                                        if (owner != null) ownerName = String.valueOf(owner.getEntity().getName().getString());
                                    } catch (IllegalArgumentException ignored) {}

                                    var membersList = playerTeam.getListOrEmpty("members");
                                    StringBuilder membersText = new StringBuilder();
                                    AtomicInteger offlineCount = new AtomicInteger();

                                    for (int i = 0; i < membersList.size(); i++) {
                                        membersList.getString(i).ifPresent(uuidStr -> {
                                            try {
                                                ServerPlayerEntity member = null;
                                                if (player.getEntityWorld() instanceof ServerWorld serverWorld) {
                                                    MinecraftServer server = serverWorld.getServer();
                                                    member = server.getPlayerManager().getPlayer(UUID.fromString(uuidStr));
                                                }
                                                if (member != null) {
                                                    if (membersText.length() > 0) membersText.append(", ");
                                                    membersText.append(member.getEntity().getName().getString());
                                                } else {
                                                    offlineCount.getAndIncrement();
                                                }
                                            } catch (IllegalArgumentException ignored) {
                                                offlineCount.getAndIncrement();
                                            }
                                        });
                                    }

                                    if (offlineCount.get() > 0) {
                                        if (membersText.length() > 0) membersText.append(", ");
                                        membersText.append("(").append(offlineCount.get()).append(") Offline");
                                    }

                                    Text infoMessage = Text.literal("§6=== Team Info ===\n")
                                            .append(Text.literal("§eTeam Name: §f" + teamName + "\n"))
                                            .append(Text.literal("§eTeam Tag: §f" + teamTag + "\n"))
                                            .append(Text.literal("§eOwner: §f" + ownerName + "\n"))
                                            .append(Text.literal("§eMembers: §f" + (membersText.length() > 0 ? membersText : "None")));

                                    context.getSource().sendFeedback(() -> infoMessage, false);

                                    return 1;
                                })
                        )

                        .then(CommandManager.literal("settings")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    try {
                                        assert player != null;
                                        datManager.get().handleSettings(player, null, null);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    return 1;
                                })
                                .then(CommandManager.argument("setting", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");

                                            NbtCompound teamData = null;
                                            for (String teamName : teams.getKeys()) {
                                                NbtCompound team = teams.getCompoundOrEmpty(teamName);
                                                assert player != null;
                                                if (team.getString("owner").orElse("").equals(player.getUuid().toString())) {
                                                    teamData = team;
                                                    break;
                                                }
                                            }

                                            if (teamData == null) return builder.buildFuture();

                                            NbtCompound settings = teamData.getCompoundOrEmpty("settings");
                                            for (String key : settings.getKeys()) builder.suggest(key);

                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String setting = StringArgumentType.getString(context, "setting");
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            try {
                                                assert player != null;
                                                datManager.get().handleSettings(player, setting, null);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                            return 1;
                                        })
                                        .then(CommandManager.argument("value", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    String setting = StringArgumentType.getString(context, "setting");
                                                    boolean value = BoolArgumentType.getBool(context, "value");
                                                    ServerPlayerEntity player = context.getSource().getPlayer();

                                                    try {
                                                        assert player != null;
                                                        datManager.get().handleSettings(player, setting, value);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }

                                                    ServerCommandSource source = context.getSource();
                                                    MinecraftServer server = source.getServer();
                                                    teamUtils.rebuildTeams(server);

                                                    context.getSource().sendFeedback(
                                                            () -> Text.literal("Setting '" + setting + "' updated to " + value),
                                                            false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("field", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("name");
                                            builder.suggest("tag");
                                            builder.suggest("color");
                                            return builder.buildFuture();
                                        })
                                        .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> {
                                                    String field = StringArgumentType.getString(ctx, "field");
                                                    if (field.equalsIgnoreCase("color")) {
                                                        for (Formatting f : Formatting.values()) {
                                                            if (f.isColor()) {
                                                                builder.suggest(f.getName());
                                                            }
                                                        }
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> {
                                                    String field = StringArgumentType.getString(context, "field");
                                                    String value = StringArgumentType.getString(context, "value");
                                                    ServerPlayerEntity player = context.getSource().getPlayer();

                                                    try {
                                                        assert player != null;
                                                        datManager.get().executeSet(player, field, value);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }

                                                    ServerCommandSource source = context.getSource();
                                                    MinecraftServer server = source.getServer();
                                                    teamUtils.rebuildTeams(server);

                                                    context.getSource().sendFeedback(
                                                            () -> Text.literal("Successfully updated  " + field),
                                                            false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )

        ));

        LOGGER.info("Commands Registered!");
    }
}
