package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.bba.allied.teamUtils.teamChatManager;
import com.bba.allied.teamUtils.teamUtils;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                Commands.literal("allied")

                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("tag", StringArgumentType.string()).executes(context -> {
                                                    String teamName = StringArgumentType.getString(context, "name");
                                                    String teamTag = StringArgumentType.getString(context, "tag");
                                                    ServerPlayer player = context.getSource().getPlayer();
                                                    assert player != null;
                                                    UUID ownerUuid = player.getUUID();

                                                    CommandSourceStack source = context.getSource();
                                                    MinecraftServer server = source.getServer();

                                                    datManager.get().addTeam(teamName, teamTag, ownerUuid);

                                                    teamUtils.rebuildTeams(server);

                                                    context.getSource().sendSuccess(
                                                            () -> Component.nullToEmpty("Successfully created team " + teamName),
                                                            false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("disband")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    assert player != null;
                                    UUID ownerUuid = player.getUUID();
                                    try {
                                        datManager.get().removeTeam(ownerUuid);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }

                                    CommandSourceStack source = context.getSource();
                                    MinecraftServer server = source.getServer();

                                    teamUtils.rebuildTeams(server);

                                    context.getSource().sendSuccess(
                                            () -> Component.nullToEmpty("Successfully Disbanded team"),
                                            false
                                    );
                                    return 1;
                                })
                        )

                        .then(Commands.literal("leave")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    assert player != null;
                                    UUID ownerUuid = player.getUUID();
                                    try {
                                        datManager.get().leaveTeam(ownerUuid);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }

                                    CommandSourceStack source = context.getSource();
                                    MinecraftServer server = source.getServer();

                                    teamUtils.rebuildTeams(server);

                                    context.getSource().sendSuccess(
                                            () -> Component.nullToEmpty("Successfully left team"),
                                            false
                                    );
                                    return 1;
                                })
                        )

                        .then(Commands.literal("tm")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    UUID uuid = player.getUUID();

                                    if (!datManager.get().isInTeam(uuid)) {
                                        context.getSource().sendFailure(Component.literal("You are not in a team!"));
                                        return 0;
                                    }

                                    boolean enabled = teamChatManager.toggle(uuid);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(enabled ? "Team Chat Enabled" : "Team Chat Disabled"),
                                            false
                                    );
                                    return 1;
                                })
                        )

                        .then(Commands.literal("join")
                                .then(Commands.argument("name", StringArgumentType.string())
                                    .executes(context -> {
                                        String teamName = StringArgumentType.getString(context, "name");
                                        ServerPlayer player = context.getSource().getPlayer();
                                        assert player != null;
                                        UUID ownerUuid = player.getUUID();

                                        CommandSourceStack source = context.getSource();
                                        MinecraftServer server = source.getServer();

                                        datManager.get().sendRequest(teamName, ownerUuid, server);

                                        context.getSource().sendSuccess(
                                                () -> Component.nullToEmpty("Sent Request to " + teamName),
                                                false
                                        );
                                        return 1;
                                    })
                                )
                        )

                        .then(Commands.literal("accept")
                                .then(Commands.argument("playerName", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            context.getSource().getServer().getPlayerList().getPlayers().forEach(player -> builder.suggest(player.getGameProfile().name()));
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayer owner = context.getSource().getPlayer();
                                            assert owner != null;
                                            UUID ownerUUID = owner.getUUID();

                                            String targetName = StringArgumentType.getString(context, "playerName");
                                            UUID targetUUID;

                                            try {
                                                targetUUID = UUID.fromString(targetName);
                                            } catch (IllegalArgumentException e) {
                                                ServerPlayer targetPlayer = context.getSource().getServer()
                                                        .getPlayerList()
                                                        .getPlayerByName(targetName);

                                                if (targetPlayer == null) {
                                                    context.getSource().sendFailure(Component.literal("Player not found or not online!"));
                                                    return 0;
                                                }

                                                targetUUID = targetPlayer.getUUID();
                                            }

                                            try {
                                                datManager.get().handleRequest(ownerUUID, targetUUID, true);
                                            } catch (CommandSyntaxException e) {
                                                context.getSource().sendFailure((Component) e.getRawMessage());
                                                return 0;
                                            } catch (IOException e) {
                                                context.getSource().sendFailure(Component.literal("An internal error occurred while saving the team data."));
                                                e.printStackTrace();
                                                return 0;
                                            }

                                            CommandSourceStack source = context.getSource();
                                            MinecraftServer server = source.getServer();
                                            teamUtils.rebuildTeams(server);

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Accepted join request from " + targetName),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("deny")
                                .then(Commands.argument("playerName", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            context.getSource().getServer().getPlayerList().getPlayers().forEach(player -> builder.suggest(player.getGameProfile().name()));
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayer owner = context.getSource().getPlayer();
                                            assert owner != null;
                                            UUID ownerUUID = owner.getUUID();

                                            String targetName = StringArgumentType.getString(context, "playerName");
                                            ServerPlayer targetPlayer = context.getSource().getServer()
                                                    .getPlayerList().getPlayerByName(targetName);

                                            if (targetPlayer == null) {
                                                context.getSource().sendFailure(Component.literal("Player not found or not online!"));
                                                return 0;
                                            }

                                            UUID targetUUID = targetPlayer.getUUID();

                                            try {
                                                datManager.get().handleRequest(ownerUUID, targetUUID, false);
                                            } catch (IOException | CommandSyntaxException e) {
                                                throw new RuntimeException(e);
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Denied join request from " + targetName),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("invite")
                                .then(Commands.argument("playerName", StringArgumentType.word())
                                        .executes(context -> {
                                            ServerPlayer owner = context.getSource().getPlayer();
                                            ServerPlayer target = context.getSource()
                                                    .getServer()
                                                    .getPlayerList()
                                                    .getPlayerByName(StringArgumentType.getString(context, "playerName"));

                                            if (target == null) {
                                                context.getSource().sendFailure(Component.literal("Player not online!"));
                                                return 0;
                                            }

                                            try {
                                                datManager.get().sendInvite(
                                                        owner.getUUID(),
                                                        target.getUUID(),
                                                        context.getSource().getServer()
                                                );
                                            } catch (CommandSyntaxException e) {
                                                context.getSource().sendFailure((Component) e.getRawMessage());
                                                return 0;
                                            } catch (Exception e) {
                                                context.getSource().sendFailure(Component.literal("Internal error occured, please contact server owner."));
                                                e.printStackTrace();
                                                return 0;
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Invite sent."),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("invAccept")
                                .then(Commands.argument("teamName", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player != null) {
                                                datManager.get()
                                                        .getInvitedTeams(player.getUUID())
                                                        .forEach(builder::suggest);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player == null) return 0;

                                            String teamName = StringArgumentType.getString(context, "teamName");

                                            try {
                                                datManager.get().handleInvite(
                                                        player.getUUID(),
                                                        teamName,
                                                        true
                                                );
                                            } catch (CommandSyntaxException e) {
                                                context.getSource().sendFailure((Component) e.getRawMessage());
                                                return 0;
                                            } catch (IOException e) {
                                                context.getSource().sendFailure(Component.literal("Failed to save team data."));
                                                e.printStackTrace();
                                                return 0;
                                            }

                                            teamUtils.rebuildTeams(context.getSource().getServer());

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Joined team ")
                                                            .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW)),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("invDeny")
                                .then(Commands.argument("teamName", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player != null) {
                                                datManager.get()
                                                        .getInvitedTeams(player.getUUID())
                                                        .forEach(builder::suggest);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            if (player == null) return 0;

                                            String teamName = StringArgumentType.getString(context, "teamName");

                                            try {
                                                datManager.get().handleInvite(
                                                        player.getUUID(),
                                                        teamName,
                                                        false
                                                );
                                            } catch (CommandSyntaxException e) {
                                                context.getSource().sendFailure((Component) e.getRawMessage());
                                                return 0;
                                            } catch (IOException e) {
                                                context.getSource().sendFailure(Component.literal("Failed to save team data."));
                                                e.printStackTrace();
                                                return 0;
                                            }

                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Denied invite from ")
                                                            .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW)),
                                                    false
                                            );
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("info")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    String playerUuid = player.getUUID().toString();
                                    CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");

                                    CompoundTag playerTeam = null;
                                    String teamName = null;

                                    for (String key : teams.keySet()) {
                                        CompoundTag team = teams.getCompoundOrEmpty(key);

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
                                        context.getSource().sendSuccess(() -> Component.literal("You are not in a team!"), false);
                                        return 0;
                                    }

                                    String teamTag = playerTeam.getString("teamTag").orElse(teamName);
                                    String ownerUuid = playerTeam.getString("owner").orElse("");
                                    String ownerName = "Unknown";

                                    ServerPlayer owner = null;
                                    try {
                                        if (player.level() instanceof ServerLevel serverWorld) {
                                            MinecraftServer server = serverWorld.getServer();
                                            owner = server.getPlayerList().getPlayer(UUID.fromString(ownerUuid));
                                        }
                                        if (owner != null) ownerName = String.valueOf(owner.asLivingEntity().getName().getString());
                                    } catch (IllegalArgumentException ignored) {}

                                    var membersList = playerTeam.getListOrEmpty("members");
                                    StringBuilder membersText = new StringBuilder();
                                    AtomicInteger offlineCount = new AtomicInteger();

                                    for (int i = 0; i < membersList.size(); i++) {
                                        membersList.getString(i).ifPresent(uuidStr -> {
                                            try {
                                                ServerPlayer member = null;
                                                if (player.level() instanceof ServerLevel serverWorld) {
                                                    MinecraftServer server = serverWorld.getServer();
                                                    member = server.getPlayerList().getPlayer(UUID.fromString(uuidStr));
                                                }
                                                if (member != null) {
                                                    if (membersText.length() > 0) membersText.append(", ");
                                                    membersText.append(member.asLivingEntity().getName().getString());
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

                                    Component infoMessage = Component.literal("§6=== Team Info ===\n")
                                            .append(Component.literal("§eTeam Name: §f" + teamName + "\n"))
                                            .append(Component.literal("§eTeam Tag: §f" + teamTag + "\n"))
                                            .append(Component.literal("§eOwner: §f" + ownerName + "\n"))
                                            .append(Component.literal("§eMembers: §f" + (membersText.length() > 0 ? membersText : "None")));

                                    context.getSource().sendSuccess(() -> infoMessage, false);

                                    return 1;
                                })
                        )

                        .then(Commands.literal("settings")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    assert player != null;

                                    String teamName = datManager.get().getTeam(player.getUUID());
                                    if (teamName != null) {
                                        ListTag blocked = datManager.get()
                                                .getData()
                                                .getCompoundOrEmpty("settings")
                                                .getListOrEmpty("blockTeamsSettings");

                                        for (int i = 0; i < blocked.size(); i++) {
                                            if (teamName.equalsIgnoreCase(blocked.getString(i).orElse(""))) {
                                                context.getSource().sendFailure(
                                                        Component.literal(
                                                                "Server Admin has disabled you from changing your team settings, please contact the Server's Admin!"
                                                        )
                                                );
                                                return 0;
                                            }
                                        }
                                    }

                                    try {
                                        assert player != null;
                                        datManager.get().handleSettings(player, null, null);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    return 1;
                                })
                                .then(Commands.argument("setting", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");

                                            CompoundTag teamData = null;
                                            for (String teamName : teams.keySet()) {
                                                CompoundTag team = teams.getCompoundOrEmpty(teamName);
                                                assert player != null;
                                                if (team.getString("owner").orElse("").equals(player.getUUID().toString())) {
                                                    teamData = team;
                                                    break;
                                                }
                                            }

                                            if (teamData == null) return builder.buildFuture();

                                            CompoundTag settings = teamData.getCompoundOrEmpty("settings");
                                            for (String key : settings.keySet()) builder.suggest(key);

                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayer();
                                            assert player != null;

                                            String teamName = datManager.get().getTeam(player.getUUID());
                                            if (teamName != null) {
                                                ListTag blocked = datManager.get()
                                                        .getData()
                                                        .getCompoundOrEmpty("settings")
                                                        .getListOrEmpty("blockTeamsSettings");

                                                for (int i = 0; i < blocked.size(); i++) {
                                                    if (teamName.equalsIgnoreCase(blocked.getString(i).orElse(""))) {
                                                        context.getSource().sendFailure(
                                                                Component.literal(
                                                                        "Server Admin has disabled you from changing your team settings, please contact the Server's Admin!"
                                                                )
                                                        );
                                                        return 0;
                                                    }
                                                }
                                            }

                                            String setting = StringArgumentType.getString(context, "setting");
                                            try {
                                                assert player != null;
                                                datManager.get().handleSettings(player, setting, null);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                            return 1;
                                        })
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayer();
                                                    assert player != null;

                                                    String teamName = datManager.get().getTeam(player.getUUID());
                                                    if (teamName != null) {
                                                        ListTag blocked = datManager.get()
                                                                .getData()
                                                                .getCompoundOrEmpty("settings")
                                                                .getListOrEmpty("blockTeamsSettings");

                                                        for (int i = 0; i < blocked.size(); i++) {
                                                            if (teamName.equalsIgnoreCase(blocked.getString(i).orElse(""))) {
                                                                context.getSource().sendFailure(
                                                                        Component.literal(
                                                                                "Server Admin has disabled you from changing your team settings, please contact the Server's Admin!"
                                                                        )
                                                                );
                                                                return 0;
                                                            }
                                                        }
                                                    }

                                                    String setting = StringArgumentType.getString(context, "setting");
                                                    boolean value = BoolArgumentType.getBool(context, "value");

                                                    try {
                                                        assert player != null;
                                                        datManager.get().handleSettings(player, setting, value);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }

                                                    CommandSourceStack source = context.getSource();
                                                    MinecraftServer server = source.getServer();
                                                    teamUtils.rebuildTeams(server);

                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal("Setting '" + setting + "' updated to " + value),
                                                            false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("set")
                                .then(Commands.argument("field", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("name");
                                            builder.suggest("tag");
                                            builder.suggest("color");
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> {
                                                    String field = StringArgumentType.getString(ctx, "field");
                                                    if (field.equalsIgnoreCase("color")) {
                                                        for (ChatFormatting f : ChatFormatting.values()) {
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
                                                    ServerPlayer player = context.getSource().getPlayer();

                                                    try {
                                                        assert player != null;
                                                        datManager.get().executeSet(player, field, value);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }

                                                    CommandSourceStack source = context.getSource();
                                                    MinecraftServer server = source.getServer();
                                                    teamUtils.rebuildTeams(server);

                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal("Successfully updated " + field),
                                                            false
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("kick")
                                .then(Commands.argument("playerName", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            ServerPlayer owner = context.getSource().getPlayer();
                                            if (owner == null) return builder.buildFuture();

                                            CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
                                            String ownerStr = owner.getUUID().toString();
                                            CompoundTag teamData = null;

                                            for (String tName : teams.keySet()) {
                                                CompoundTag t = teams.getCompoundOrEmpty(tName);
                                                if (ownerStr.equals(t.getString("owner").orElse(""))) {
                                                    teamData = t;
                                                    break;
                                                }
                                            }

                                            if (teamData == null) return builder.buildFuture();
                                            MinecraftServer server = context.getSource().getServer();
                                            ListTag members = teamData.getListOrEmpty("members");
                                            for (int i = 0; i < members.size(); i++) {
                                                String memberUUID = members.getString(i).orElse("");
                                                if (!ownerStr.equals(memberUUID)) {
                                                    ServerPlayer member = server.getPlayerList().getPlayer(UUID.fromString(memberUUID));

                                                    if (member != null) builder.suggest(member.getGameProfile().name());
                                                }
                                            }

                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            ServerPlayer owner = context.getSource().getPlayer();
                                            if (owner == null) return 0;

                                            String targetName = StringArgumentType.getString(context, "playerName");
                                            MinecraftServer server = context.getSource().getServer();
                                            ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
                                            if (target == null) {
                                                context.getSource().sendFailure(Component.literal("Player not found or not online!"));
                                                return 0;
                                            }

                                            try {
                                                datManager.get().kickMember(owner, target);
                                            } catch (IOException e) {
                                                context.getSource().sendFailure(Component.literal("Failed to save team data."));
                                                e.printStackTrace();
                                                return 0;
                                            }

                                            teamUtils.rebuildTeams(server);

                                            return 1;
                                        })
                                )
                        )

        ));

        LOGGER.info("Commands Registered!");
    }
}
