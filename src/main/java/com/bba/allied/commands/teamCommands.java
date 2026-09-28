package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.bba.allied.storage.teamStorage;
import com.bba.allied.teamUtils.teamFeatures;
import com.bba.allied.teamUtils.teamUtils;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.UUID;

// Roles, ownership transfer, marks and team storage
public class teamCommands {

    // Names of everyone in the player's team except the owner
    private static final SuggestionProvider<CommandSourceStack> TEAM_MEMBERS = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return builder.buildFuture();

        datManager dm = datManager.get();
        String teamName = dm.getTeam(player.getUUID());
        if (teamName == null) return builder.buildFuture();

        CompoundTag team = dm.getTeamData(teamName);
        MinecraftServer server = context.getSource().getServer();
        for (UUID uuid : dm.getTeamPlayers(team)) {
            if (dm.getRole(team, uuid) != datManager.Role.OWNER) builder.suggest(dm.nameOf(server, uuid));
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> TEAM_MARKS = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return builder.buildFuture();

        String teamName = datManager.get().getTeam(player.getUUID());
        if (teamName == null) return builder.buildFuture();

        CompoundTag marks = datManager.get().getTeamData(teamName).getCompoundOrEmpty("marks");
        for (String key : marks.keySet()) {
            builder.suggest(StringArgumentType.escapeIfRequired(marks.getCompoundOrEmpty(key).getStringOr("name", key)));
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> ALL_TEAMS = (context, builder) -> {
        datManager.get().getData().getCompoundOrEmpty("teams").keySet()
                .forEach(name -> builder.suggest(StringArgumentType.escapeIfRequired(name)));
        return builder.buildFuture();
    };

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("allied")
                    .requires(CommandSourceStack::isPlayer)

                    .then(Commands.literal("storage")
                            .executes(context -> {
                                teamStorage.open(context.getSource().getPlayerOrException());
                                return 1;
                            }))

                    .then(Commands.literal("mark")
                            .then(Commands.argument("name", StringArgumentType.string())
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        return mark(context, player.blockPosition());
                                    })
                                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                            .executes(context -> mark(context, BlockPosArgument.getBlockPos(context, "pos"))))))

                    .then(Commands.literal("unmark")
                            .then(Commands.argument("name", StringArgumentType.string())
                                    .suggests(TEAM_MARKS)
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        String name = StringArgumentType.getString(context, "name");
                                        try {
                                            teamFeatures.removeMark(player, name);
                                        } catch (IOException e) {
                                            throw saveFailed(e);
                                        }
                                        context.getSource().sendSuccess(() -> Component.literal("Removed mark " + name), false);
                                        return 1;
                                    })))

                    .then(Commands.literal("marks")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                Component list = teamFeatures.listMarks(player);
                                context.getSource().sendSuccess(() -> list, false);
                                return 1;
                            }))

                    .then(Commands.literal("promote")
                            .then(Commands.argument("playerName", StringArgumentType.word())
                                    .suggests(TEAM_MEMBERS)
                                    .executes(context -> {
                                        ServerPlayer owner = context.getSource().getPlayerOrException();
                                        MinecraftServer server = context.getSource().getServer();
                                        UUID target = datManager.get().resolvePlayer(server, StringArgumentType.getString(context, "playerName"));
                                        try {
                                            datManager.get().promote(owner.getUUID(), target);
                                        } catch (IOException e) {
                                            throw saveFailed(e);
                                        }
                                        announceRole(server, owner, target, " was promoted to officer");
                                        return 1;
                                    })))

                    .then(Commands.literal("demote")
                            .then(Commands.argument("playerName", StringArgumentType.word())
                                    .suggests(TEAM_MEMBERS)
                                    .executes(context -> {
                                        ServerPlayer owner = context.getSource().getPlayerOrException();
                                        MinecraftServer server = context.getSource().getServer();
                                        UUID target = datManager.get().resolvePlayer(server, StringArgumentType.getString(context, "playerName"));
                                        try {
                                            datManager.get().demote(owner.getUUID(), target);
                                        } catch (IOException e) {
                                            throw saveFailed(e);
                                        }
                                        announceRole(server, owner, target, " is no longer an officer");
                                        return 1;
                                    })))

                    .then(Commands.literal("transfer")
                            .then(Commands.argument("playerName", StringArgumentType.word())
                                    .suggests(TEAM_MEMBERS)
                                    // First run asks for confirmation with a clickable button
                                    .executes(context -> {
                                        ServerPlayer owner = context.getSource().getPlayerOrException();
                                        MinecraftServer server = context.getSource().getServer();
                                        datManager dm = datManager.get();

                                        String teamName = dm.requireOwnedTeam(owner.getUUID());
                                        UUID target = dm.resolvePlayer(server, StringArgumentType.getString(context, "playerName"));
                                        if (dm.getRole(dm.getTeamData(teamName), target) == null) {
                                            throw error("The new owner must be a member of your team!");
                                        }

                                        String targetName = dm.nameOf(server, target);
                                        Component confirm = Component.literal("[CONFIRM]").withStyle(style -> style
                                                .withColor(ChatFormatting.RED)
                                                .withClickEvent(new ClickEvent.RunCommand("/allied transfer " + targetName + " confirm"))
                                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Give " + targetName + " ownership of " + teamName))));

                                        context.getSource().sendSuccess(() -> Component.literal("Transfer ownership of ")
                                                .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW))
                                                .append(Component.literal(" to " + targetName + "? You will become an officer. "))
                                                .append(confirm), false);
                                        return 1;
                                    })
                                    .then(Commands.literal("confirm")
                                            .executes(context -> {
                                                ServerPlayer owner = context.getSource().getPlayerOrException();
                                                MinecraftServer server = context.getSource().getServer();
                                                datManager dm = datManager.get();

                                                String teamName = dm.requireOwnedTeam(owner.getUUID());
                                                UUID target = dm.resolvePlayer(server, StringArgumentType.getString(context, "playerName"));
                                                try {
                                                    dm.transferOwnership(teamName, target);
                                                } catch (IOException e) {
                                                    throw saveFailed(e);
                                                }

                                                teamUtils.rebuildTeams(server);
                                                dm.messageTeam(server, dm.getTeamData(teamName), Component.literal(dm.nameOf(server, target))
                                                        .withStyle(ChatFormatting.YELLOW)
                                                        .append(Component.literal(" is now the owner of " + teamName).withStyle(ChatFormatting.WHITE)));
                                                return 1;
                                            })))));

            dispatcher.register(Commands.literal("alliedAdmin")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))

                    .then(Commands.literal("storage")
                            .then(Commands.argument("teamName", StringArgumentType.string())
                                    .suggests(ALL_TEAMS)
                                    .then(Commands.argument("value", BoolArgumentType.bool())
                                            .executes(context -> {
                                                String teamName = StringArgumentType.getString(context, "teamName");
                                                boolean value = BoolArgumentType.getBool(context, "value");

                                                CompoundTag team = datManager.get().getTeamData(teamName);
                                                if (team.isEmpty()) throw error("Team '" + teamName + "' does not exist!");

                                                // Disabling closes an open storage on the next tick (StorageContainer.stillValid)
                                                team.putBoolean("storageEnabled", value);
                                                try {
                                                    datManager.get().save();
                                                } catch (IOException e) {
                                                    throw saveFailed(e);
                                                }

                                                context.getSource().sendSuccess(() -> Component.literal(
                                                        "Team storage " + (value ? "enabled" : "disabled") + " for " + teamName), true);
                                                return 1;
                                            }))))

                    // Rescue a team whose owner stopped playing
                    .then(Commands.literal("transfer")
                            .then(Commands.argument("teamName", StringArgumentType.string())
                                    .suggests(ALL_TEAMS)
                                    .then(Commands.argument("playerName", StringArgumentType.word())
                                            .executes(context -> {
                                                String teamName = StringArgumentType.getString(context, "teamName");
                                                MinecraftServer server = context.getSource().getServer();
                                                datManager dm = datManager.get();
                                                UUID target = dm.resolvePlayer(server, StringArgumentType.getString(context, "playerName"));

                                                try {
                                                    dm.transferOwnership(teamName, target);
                                                } catch (IOException e) {
                                                    throw saveFailed(e);
                                                }

                                                teamUtils.rebuildTeams(server);
                                                String targetName = dm.nameOf(server, target);
                                                dm.messageTeam(server, dm.getTeamData(teamName), Component.literal(targetName)
                                                        .withStyle(ChatFormatting.YELLOW)
                                                        .append(Component.literal(" is now the owner of " + teamName + " (set by a server admin)").withStyle(ChatFormatting.WHITE)));
                                                context.getSource().sendSuccess(() -> Component.literal(targetName + " now owns " + teamName), true);
                                                return 1;
                                            })))));
        });
    }

    // Disbanding would orphan the storage file, so the team has to empty it first
    public static void checkCanDisband(ServerPlayer owner) throws CommandSyntaxException {
        datManager dm = datManager.get();
        String teamName = dm.getTeam(owner.getUUID());
        if (teamName == null) return;

        CompoundTag team = dm.getTeamData(teamName);
        String teamId = team.getStringOr("id", "");
        if (teamId.isEmpty()) return;

        if (teamStorage.isOpen(teamId)) {
            throw error("Someone is using the team storage, try again when it's closed.");
        }
        try {
            if (teamStorage.hasItems(owner.level().getServer(), teamId)) {
                throw error("Empty the team storage (/allied storage) before disbanding, or its items will be lost.");
            }
        } catch (IOException e) {
            throw error("The team storage could not be read. Please contact a server admin.");
        }
    }

    private static int mark(CommandContext<CommandSourceStack> context, BlockPos pos) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            teamFeatures.addMark(player, StringArgumentType.getString(context, "name"), pos);
        } catch (IOException e) {
            throw saveFailed(e);
        }
        return 1;
    }

    private static void announceRole(MinecraftServer server, ServerPlayer owner, UUID target, String text) {
        datManager dm = datManager.get();
        String teamName = dm.getTeam(owner.getUUID());
        if (teamName == null) return;
        dm.messageTeam(server, dm.getTeamData(teamName), Component.literal(dm.nameOf(server, target))
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    private static CommandSyntaxException saveFailed(IOException e) {
        datManager.LOGGER.error("Failed to save team data", e);
        return error("Failed to save team data, please contact a server admin.");
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
