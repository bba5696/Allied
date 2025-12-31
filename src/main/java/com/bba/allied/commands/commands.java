package com.bba.allied.commands;

import com.bba.allied.data.datManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

                                            datManager.get().sendRequest(teamName, ownerUuid);

                                            context.getSource().sendFeedback(
                                                    () -> Text.of("Sent Request to " + teamName),
                                                    false
                                            );
                                            return 1;
                                        })
                                    )
                            )
            );


            // You can add more subcommands: join, leave, delete, list, etc.
        });

        LOGGER.info("Commands Registered!");
    }
}
