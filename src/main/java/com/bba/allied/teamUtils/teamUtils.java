package com.bba.allied.teamUtils;

import com.bba.allied.data.datManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public class teamUtils {
    // Scoreboard teams created by Allied are prefixed so vanilla /team and other mods' teams are left alone
    public static final String TEAM_PREFIX = "allied_";

    public static final String MOD_ID = "Minecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void refreshTabForPlayer(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                player
        );
        server.getPlayerList().broadcastAll(packet);
    }

    public static void refreshAllTablist(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                    player
            );
            server.getPlayerList().broadcastAll(packet);
        }
    }

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((signedMessage, player, params) -> {
            String rawText = signedMessage.decoratedContent().getString();
            Component formatted = formatTeamChat(player, rawText);

            UUID uuid = player.getUUID();

            String teamName = teamChatManager.isEnabled(uuid) ? datManager.get().getTeam(uuid) : null;
            if (teamChatManager.isEnabled(uuid) && teamName == null) {
                teamChatManager.disable(uuid);
            }

            if (teamName != null) {
                CompoundTag teamData = datManager.get().getData()
                        .getCompoundOrEmpty("teams")
                        .getCompoundOrEmpty(teamName);

                teamData.getString("owner").ifPresent(ownerStr -> {
                    try {
                        ServerPlayer owner = player.level().getServer().getPlayerList()
                                .getPlayer(UUID.fromString(ownerStr));
                        if (owner != null) owner.sendSystemMessage(formatted);
                    } catch (Exception ignored) {}
                });

                var members = teamData.getListOrEmpty("members");
                for (int i = 0; i < members.size(); i++) {
                    members.getString(i).ifPresent(memberStr -> {
                        try {
                            ServerPlayer member = player.level().getServer().getPlayerList()
                                    .getPlayer(UUID.fromString(memberStr));
                            if (member != null) member.sendSystemMessage(formatted);
                        } catch (Exception ignored) {}
                    });
                }
            } else {
                MinecraftServer server = player.level().getServer();
                if (server != null) {
                    server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(formatted));
                }
                LOGGER.info("{}", formatted.getString());
            }

            return false;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> teamUtils.handleFriendlyFire(entity, source));
        ServerTickEvents.END_SERVER_TICK.register(highlightManager::tick);
    }

    private static Component formatTeamChat(ServerPlayer player, String originalMessage) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String playerUuid = player.getUUID().toString();

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            if (team.getString("owner").orElse("").equals(playerUuid)) {
                return buildChatMessage(player, originalMessage, team, teamName);
            }

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                if (members.getString(i).orElse("").equals(playerUuid)) {
                    return buildChatMessage(player, originalMessage, team, teamName);
                }
            }
        }
        return Component.literal("<")
                .append(player.getDisplayName())
                .append("> ")
                .append(originalMessage).withStyle(ChatFormatting.WHITE);
    }

    private static Component buildChatMessage(
            ServerPlayer player,
            String message,
            CompoundTag team,
            String internalTeamName
    ) {
        boolean useTag = team
                .getCompoundOrEmpty("settings")
                .getBoolean("chatUseTag")
                .orElse(false);

        String colorStr = team
                .getString("tagColor")
                .orElse("WHITE");

        ChatFormatting color;
        try {
            color = ChatFormatting.valueOf(colorStr.toUpperCase());
        } catch (Exception e) {
            color = ChatFormatting.WHITE;
        }

        PlayerTeam scoreboardTeam = player.getTeam();

        Component prefix = Component.empty();
        Component playerName = Component.literal(player.getName().getString()).withStyle(ChatFormatting.WHITE);

        if (scoreboardTeam != null) {
            prefix = scoreboardTeam.getPlayerPrefix();
        }
        Component teamName = Component.literal("[").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(internalTeamName).withStyle(color))
                .append(Component.literal("] ")).withStyle(ChatFormatting.WHITE);

        prefix = useTag ? prefix : teamName;

        UUID uuid = player.getUUID();
        boolean teamChatEnabled = teamChatManager.isEnabled(uuid);

        if (teamChatEnabled) {
            prefix = Component.literal("[").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("TEAM").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("] ")).withStyle(ChatFormatting.WHITE);
        }

        return Component.empty()
                .append(prefix)
                .append(Component.literal("<"))
                .append(playerName)
                .append(Component.literal("> "))
                .append(Component.literal(message));
    }

    public static void rebuildTeams(
            MinecraftServer server
    ) {
        removeAllTeams(server);

        CompoundTag teamsNBT = datManager.get().getData().getCompoundOrEmpty("teams");

        for (String internalTeamName : teamsNBT.keySet()) {
            CompoundTag teamData = teamsNBT.getCompoundOrEmpty(internalTeamName);
            if (teamData.isEmpty()) continue;

            String colorStr = teamData
                    .getString("tagColor")
                    .orElse("WHITE");

            TeamColor teamColor;
            try {
                teamColor = TeamColor.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                teamColor = TeamColor.WHITE;
            }

            PlayerTeam scoreboardTeam = addTeam(server, internalTeamName, teamColor);
            scoreboardTeam.setSeeFriendlyInvisibles(
                    teamData.getCompoundOrEmpty("settings").getBoolean("highlight").orElse(false)
            );

            teamData.getString("owner").ifPresent(ownerUuidStr -> {
                try {
                    UUID uuid = UUID.fromString(ownerUuidStr);
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        addPlayerToTeam(server, player, scoreboardTeam);
                    }
                } catch (IllegalArgumentException ignored) {}
            });

            var members = teamData.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                members.getString(i).ifPresent(memberUuidStr -> {
                    try {
                        UUID uuid = UUID.fromString(memberUuidStr);
                        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                        if (player != null) {
                            addPlayerToTeam(server, player, scoreboardTeam);
                        }
                    } catch (IllegalArgumentException ignored) {}
                });
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updateOverheadName(server, player);
        }

        refreshAllTablist(server);
    }

    public static void updateOverheadName(MinecraftServer server, ServerPlayer player) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String uuid = player.getUUID().toString();

        for (String internalTeamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(internalTeamName);

            boolean isOwner = teamData.getString("owner").orElse("").equals(uuid);
            boolean isMember = teamData.getListOrEmpty("members").stream()
                    .anyMatch(e -> e.asString().orElse("").equals(uuid));

            if (isOwner || isMember) {
                String tag = teamData.getString("teamTag").orElse(internalTeamName).toUpperCase();
                String colorStr = teamData.getString("tagColor").orElse("WHITE");
                ChatFormatting tagColor;

                try {
                    tagColor = ChatFormatting.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    tagColor = ChatFormatting.WHITE;
                }

                Component prefix = Component.literal("[")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(tag).withStyle(tagColor))
                        .append(Component.literal("] ").withStyle(ChatFormatting.WHITE));

                ServerScoreboard scoreboard = server.getScoreboard();
                String teamId = scoreboardId(internalTeamName);
                PlayerTeam team = scoreboard.getPlayerTeam(teamId);
                if (team == null) {
                    team = scoreboard.addPlayerTeam(teamId);
                }

                team.setPlayerPrefix(prefix);
                team.setPlayerSuffix(Component.empty());
                team.setColor(Optional.of(TeamColor.WHITE));
                scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
                return;
            }
        }

        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam current = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), current);
        }
    }

    public static void removeAllTeams(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();

        // Older versions used unprefixed ids, so also clear those for teams we know about
        Set<String> legacyIds = new HashSet<>();
        for (String teamName : datManager.get().getData().getCompoundOrEmpty("teams").keySet()) {
            legacyIds.add(toTeamId(teamName));
        }

        for (PlayerTeam team : scoreboard.getPlayerTeams().toArray(PlayerTeam[]::new)) {
            if (team.getName().startsWith(TEAM_PREFIX) || legacyIds.contains(team.getName())) {
                scoreboard.removePlayerTeam(team);
            }
        }
    }

    public static String toTeamId(String name) {
        return name.toLowerCase().replaceAll("\\s+", "");
    }

    public static String scoreboardId(String name) {
        return TEAM_PREFIX + toTeamId(name);
    }

    public static PlayerTeam addTeam(
            MinecraftServer server,
            String fullName,
            TeamColor color
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();
        String teamId = scoreboardId(fullName);

        PlayerTeam team = scoreboard.getPlayerTeam(teamId);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamId);
        }

        team.setDisplayName(Component.literal(fullName));
        team.setColor(Optional.of(color));

        return team;
    }

    public static void addPlayerToTeam(
            MinecraftServer server,
            ServerPlayer player,
            PlayerTeam team
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    public static boolean handleFriendlyFire(LivingEntity victim, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return true;
        }

        if (!(victim instanceof ServerPlayer victimPlayer)) {
            return true;
        }

        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");

        String attackerUuid = attacker.getUUID().toString();
        String victimUuid = victimPlayer.getUUID().toString();

        String attackerTeam = findPlayersTeam(teams, attackerUuid);
        String victimTeam   = findPlayersTeam(teams, victimUuid);

        if (attackerTeam == null || !attackerTeam.equals(victimTeam)) {
            return true;
        }

        CompoundTag teamData = teams.getCompoundOrEmpty(attackerTeam);

        return teamData
                .getCompoundOrEmpty("settings")
                .getBoolean("friendlyFire")
                .orElse(false);
    }

    private static String findPlayersTeam(CompoundTag teams, String uuid) {
        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            if (team.getString("owner").orElse("").equals(uuid)) {
                return teamName;
            }

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                if (members.getString(i).orElse("").equals(uuid)) {
                    return teamName;
                }
            }
        }
        return null;
    }
}
