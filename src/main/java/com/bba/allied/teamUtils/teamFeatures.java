package com.bba.allied.teamUtils;

import com.bba.allied.data.datManager;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.UUID;

// Death coordinates and shared waypoints (marks). Pure information, no teleporting.
public final class teamFeatures {
    public static final int MAX_MARKS = 30;
    public static final int MAX_MARK_NAME_LENGTH = 24;

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) announceDeath(player);
        });
    }

    private static void announceDeath(ServerPlayer player) {
        datManager dm = datManager.get();
        String teamName = dm.getTeam(player.getUUID());
        if (teamName == null) return;

        CompoundTag team = dm.getTeamData(teamName);
        if (!team.getCompoundOrEmpty("settings").getBooleanOr("deathCoords", true)) return;

        BlockPos pos = player.blockPosition();
        String dimension = player.level().dimension().identifier().toString();

        Component message = teamPrefix()
                .append(Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" died at "))
                .append(coords(pos))
                .append(Component.literal(" in " + dimensionName(dimension)).withStyle(ChatFormatting.GRAY));

        dm.messageTeam(player.level().getServer(), team, message);
    }

    public static void addMark(ServerPlayer player, String name, BlockPos pos) throws CommandSyntaxException, IOException {
        datManager dm = datManager.get();
        String teamName = dm.requireTeam(player.getUUID());
        CompoundTag team = dm.getTeamData(teamName);
        CompoundTag marks = team.getCompoundOrEmpty("marks");

        if (name.length() > MAX_MARK_NAME_LENGTH) {
            throw error("Mark names can be at most " + MAX_MARK_NAME_LENGTH + " characters!");
        }

        String key = name.toLowerCase();
        CompoundTag existing = marks.getCompoundOrEmpty(key);
        if (!existing.isEmpty() && !canEdit(team, existing, player.getUUID())) {
            throw error("A mark called '" + existing.getStringOr("name", name) + "' already exists and was set by someone else.");
        }
        if (existing.isEmpty() && marks.size() >= MAX_MARKS) {
            throw error("Your team already has " + MAX_MARKS + " marks, remove one with /allied unmark first.");
        }

        CompoundTag mark = new CompoundTag();
        mark.putString("name", name);
        mark.putInt("x", pos.getX());
        mark.putInt("y", pos.getY());
        mark.putInt("z", pos.getZ());
        mark.putString("dimension", player.level().dimension().identifier().toString());
        mark.putString("by", player.getUUID().toString());

        marks.put(key, mark);
        team.put("marks", marks);
        dm.save();

        dm.messageTeam(player.level().getServer(), team, teamPrefix()
                .append(Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" marked "))
                .append(Component.literal(name).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" at "))
                .append(coords(pos))
                .append(Component.literal(" in " + dimensionName(mark.getStringOr("dimension", ""))).withStyle(ChatFormatting.GRAY)));
    }

    public static void removeMark(ServerPlayer player, String name) throws CommandSyntaxException, IOException {
        datManager dm = datManager.get();
        String teamName = dm.requireTeam(player.getUUID());
        CompoundTag team = dm.getTeamData(teamName);
        CompoundTag marks = team.getCompoundOrEmpty("marks");

        String key = name.toLowerCase();
        CompoundTag mark = marks.getCompoundOrEmpty(key);
        if (mark.isEmpty()) throw error("Your team has no mark called '" + name + "'.");
        if (!canEdit(team, mark, player.getUUID())) {
            throw error("Only the player who set this mark, officers and the owner can remove it.");
        }

        marks.remove(key);
        dm.save();
    }

    public static Component listMarks(ServerPlayer player) throws CommandSyntaxException {
        datManager dm = datManager.get();
        String teamName = dm.requireTeam(player.getUUID());
        CompoundTag marks = dm.getTeamData(teamName).getCompoundOrEmpty("marks");
        MinecraftServer server = player.level().getServer();

        if (marks.isEmpty()) {
            return Component.literal("Your team has no marks yet. Add one with /allied mark <name>").withStyle(ChatFormatting.GRAY);
        }

        MutableComponent list = Component.literal("Team marks (" + marks.size() + "/" + MAX_MARKS + "):").withStyle(ChatFormatting.GOLD);
        String playerDimension = player.level().dimension().identifier().toString();

        for (String key : marks.keySet().stream().sorted().toList()) {
            CompoundTag mark = marks.getCompoundOrEmpty(key);
            BlockPos pos = new BlockPos(mark.getIntOr("x", 0), mark.getIntOr("y", 0), mark.getIntOr("z", 0));
            String dimension = mark.getStringOr("dimension", "");

            MutableComponent line = Component.literal("\n- ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(mark.getStringOr("name", key)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                    .append(coords(pos))
                    .append(Component.literal(" " + dimensionName(dimension)).withStyle(ChatFormatting.GRAY));

            if (dimension.equals(playerDimension)) {
                long distance = Math.round(Math.sqrt(player.blockPosition().distSqr(pos)));
                line.append(Component.literal(" (" + distance + " blocks away)").withStyle(ChatFormatting.DARK_GRAY));
            }

            try {
                String by = dm.nameOf(server, UUID.fromString(mark.getStringOr("by", "")));
                line.append(Component.literal(" by " + by).withStyle(ChatFormatting.DARK_GRAY));
            } catch (IllegalArgumentException ignored) {}

            list.append(line);
        }

        return list;
    }

    private static boolean canEdit(CompoundTag team, CompoundTag mark, UUID player) {
        datManager.Role role = datManager.get().getRole(team, player);
        return role == datManager.Role.OWNER
                || role == datManager.Role.OFFICER
                || player.toString().equalsIgnoreCase(mark.getStringOr("by", ""));
    }

    private static MutableComponent teamPrefix() {
        return Component.literal("[").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("TEAM").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("] ").withStyle(ChatFormatting.WHITE));
    }

    // Click to copy the coordinates
    private static MutableComponent coords(BlockPos pos) {
        String text = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.CopyToClipboard(pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
    }

    public static String dimensionName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dimension;
        };
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
