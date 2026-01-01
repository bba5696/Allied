package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

public class commands {
    public static final String MOD_ID = "allied";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(
                    CommandManager.literal("teams")

                            // /teams create <name> <tag>
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

                                                        context.getSource().sendFeedback(
                                                                () -> Text.of("Successfully created team " + teamName),
                                                                false
                                                        );
                                                        return 1;
                                                    })
                                            )
                                    )
                            )

                            // /teams disband
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

                                        context.getSource().sendFeedback(
                                                () -> Text.of("Successfully Disbanded team"),
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

                            // /teams accept <playerName>
                            .then(CommandManager.literal("accept")
                                    .then(CommandManager.argument("playerName", StringArgumentType.word())
                                            .suggests((context, builder) -> {
                                                // Suggest all online player names
                                                context.getSource().getServer().getPlayerManager().getPlayerList().forEach(player -> {
                                                    builder.suggest(player.getGameProfile().name());
                                                });
                                                return builder.buildFuture();
                                            })
                                            .executes(context -> {
                                                ServerPlayerEntity owner = context.getSource().getPlayer();
                                                assert owner != null;
                                                UUID ownerUUID = owner.getUuid();

                                                String targetName = StringArgumentType.getString(context, "playerName");
                                                UUID targetUUID = null;

                                                try {
                                                    targetUUID = UUID.fromString(targetName); // if input is already a UUID string
                                                } catch (IllegalArgumentException e) {
                                                    // Not a UUID, look up online player by name
                                                    ServerPlayerEntity targetPlayer = context.getSource().getServer()
                                                            .getPlayerManager()
                                                            .getPlayer(targetName);

                                                    if (targetPlayer == null) {
                                                        context.getSource().sendError(Text.literal("Player not found or not online!"));
                                                        return 0;
                                                    }

                                                    targetUUID = targetPlayer.getUuid();
                                                }

                                                // Accept the request
                                                try {
                                                    datManager.get().handleRequest(ownerUUID, targetUUID, true);
                                                } catch (CommandSyntaxException e) {
                                                    // Show the message from the exception to the player
                                                    context.getSource().sendError((Text) e.getRawMessage());
                                                    return 0;
                                                } catch (IOException e) {
                                                    context.getSource().sendError(Text.literal("An internal error occurred while saving the team data."));
                                                    e.printStackTrace();
                                                    return 0;
                                                }

                                                context.getSource().sendFeedback(
                                                        () -> Text.literal("Accepted join request from " + targetName),
                                                        false
                                                );
                                                return 1;
                                            })
                                    )
                            )

                            // /teams deny <playerName>
                            .then(CommandManager.literal("deny")
                                    .then(CommandManager.argument("playerName", StringArgumentType.word())
                                            .suggests((context, builder) -> {
                                                // Suggest all online player names
                                                context.getSource().getServer().getPlayerManager().getPlayerList().forEach(player -> {
                                                    builder.suggest(player.getGameProfile().name());
                                                });
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

                                                // Deny the request
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


            );


        });

        LOGGER.info("Commands Registered!");
    }
}
