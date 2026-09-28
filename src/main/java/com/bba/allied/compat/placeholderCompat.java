package com.bba.allied.compat;

import com.bba.allied.data.datManager;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.ServerPlaceholderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BiFunction;

// Only loaded when Placeholder API is installed, see Allied#onInitialize
public final class placeholderCompat {

    public static void register() {
        // %allied:team_name%
        register("team_name", (teamName, team) -> Component.literal(teamName));

        // %allied:team_tag%
        register("team_tag", (teamName, team) -> Component.literal(team.getStringOr("teamTag", teamName)));

        // %allied:team_color%
        register("team_color", (teamName, team) -> Component.literal(team.getStringOr("tagColor", "white").toLowerCase()));

        // %allied:team_prefix% - "[TAG] " in the team's colour, same as the tab list
        register("team_prefix", (teamName, team) -> Component.literal("[")
                .withStyle(ChatFormatting.WHITE)
                .append(Component.literal(team.getStringOr("teamTag", teamName).toUpperCase()).withStyle(tagColor(team)))
                .append(Component.literal("] ").withStyle(ChatFormatting.WHITE)));

        // %allied:team_members% - owner included
        register("team_members", (teamName, team) -> Component.literal(String.valueOf(team.getListOrEmpty("members").size() + 1)));
    }

    private static void register(String path, BiFunction<String, CompoundTag, Component> value) {
        Placeholders.registerServer(Identifier.fromNamespaceAndPath("allied", path), (ServerPlaceholderContext ctx, String arg) -> {
            if (!ctx.hasServerPlayer()) {
                return PlaceholderResult.invalid("No player!");
            }

            String teamName = datManager.get().getTeam(ctx.serverPlayer().getUUID());
            if (teamName == null) {
                // Players without a team resolve to nothing so formats like "%allied:team_prefix%%player:name%" still work
                return PlaceholderResult.value("");
            }

            CompoundTag team = datManager.get().getData().getCompoundOrEmpty("teams").getCompoundOrEmpty(teamName);
            return PlaceholderResult.value(value.apply(teamName, team));
        });
    }

    private static ChatFormatting tagColor(CompoundTag team) {
        try {
            return ChatFormatting.valueOf(team.getStringOr("tagColor", "WHITE").toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChatFormatting.WHITE;
        }
    }
}
