package com.bba.allied.teamUtils;

import com.bba.allied.data.datManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.scoreboard.ServerScoreboard;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;

public class teamUtils {
    static NbtCompound data = datManager.get().getData();

    public static final String MOD_ID = "Minecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void refreshTabForPlayer(ServerPlayerEntity player) {
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) return; // safety check

        PlayerListS2CPacket packet = new PlayerListS2CPacket(
                PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
                player
        );
        server.getPlayerManager().sendToAll(packet);
    }


    public static void refreshAllTablist(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerListS2CPacket packet = new PlayerListS2CPacket(
                    PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
                    player
            );
            server.getPlayerManager().sendToAll(packet);
        }
    }

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((signedMessage, player, params) -> {
            // Convert original message to Text
            String rawText = signedMessage.getContent().getString(); // raw string only
            Text formatted = formatTeamChat(player, rawText);

            // Broadcast manually
            player.getEntityWorld().getPlayers().forEach(p -> {
                p.sendMessage(formatted, false); // false = not system message
            });

            LOGGER.info("{}", formatted.getString());


            return false;
        });
    }

    private static Text formatTeamChat(ServerPlayerEntity player, String originalMessage) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String playerUuid = player.getUuid().toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);

            // owner
            if (team.getString("owner").orElse("").equals(playerUuid)) {
                return buildChatMessage(player, originalMessage, team, teamName);
            }

            // members
            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                if (members.getString(i).orElse("").equals(playerUuid)) {
                    return buildChatMessage(player, originalMessage, team, teamName);
                }
            }
        }
        // not in a team → vanilla format
        return Text.literal("<")
                .append(player.getDisplayName())
                .append("> ")
                .append(originalMessage).formatted(Formatting.WHITE);
    }

    private static Text buildChatMessage(
            ServerPlayerEntity player,
            String message,
            NbtCompound team,
            String internalTeamName
    ) {
        boolean useTag = team
                .getCompoundOrEmpty("settings")
                .getBoolean("chatUseTag")
                .orElse(false);

        // original internal team name
        String tag = team.getString("teamTag").orElse(internalTeamName).toUpperCase();
        String label = useTag ? tag : internalTeamName;

        String colorStr = team
                .getString("tagColor")
                .orElse("WHITE");

        Formatting color;
        try {
            color = Formatting.valueOf(colorStr.toUpperCase());
        } catch (Exception e) {
            color = Formatting.WHITE;
        }

        Team scoreboardTeam = player.getScoreboardTeam();

        Text prefix = Text.empty();
        Text playerName = Text.literal(player.getName().getString()).formatted(Formatting.WHITE);

        if (scoreboardTeam != null) {
            prefix = scoreboardTeam.getPrefix(); // this is your [BBA] colored tag
        }

        // Now build the chat manually with < > around the name only
        return Text.empty()
                .append(prefix)                     // [BBA]
                .append(Text.literal("<"))          // <
                .append(playerName)                       // Player861
                .append(Text.literal("> "))         // >
                .append(Text.literal(message));
    }

    public static void rebuildTeams(
            MinecraftServer server
    ) {
        // 1. wipe teams
        removeAllTeams(server);

        // 2. recreate teams from saved data
        NbtCompound teamsNBT = data.getCompoundOrEmpty("teams");

        for (String internalTeamName : teamsNBT.getKeys()) {
            NbtCompound teamData = teamsNBT.getCompoundOrEmpty(internalTeamName);
            if (teamData.isEmpty()) continue;

            // Read tag
            String teamTag = teamData.getString("teamTag").orElse(internalTeamName);

            teamTag = teamTag.toUpperCase();

            // Read color
            String colorStr = teamData
                    .getString("tagColor")
                    .orElse("WHITE");

            Formatting teamColor;
            try {
                teamColor = Formatting.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                teamColor = Formatting.WHITE;
            }

            // Create / update scoreboard team
            Team scoreboardTeam = addTeam(server, internalTeamName, teamTag, teamColor);

            // 3. add owner
            teamData.getString("owner").ifPresent(ownerUuidStr -> {
                try {
                    UUID uuid = UUID.fromString(ownerUuidStr);
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                    if (player != null) {
                        addPlayerToTeam(server, player, scoreboardTeam);
                    }
                } catch (IllegalArgumentException ignored) {}
            });

            // 4. add members
            var members = teamData.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                members.getString(i).ifPresent(memberUuidStr -> {
                    try {
                        UUID uuid = UUID.fromString(memberUuidStr);
                        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                        if (player != null) {
                            addPlayerToTeam(server, player, scoreboardTeam);
                        }
                    } catch (IllegalArgumentException ignored) {}
                });
            }

        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            updateOverheadName(server, player);
        }

        refreshAllTablist(server);
    }

    public static void updateOverheadName(MinecraftServer server, ServerPlayerEntity player) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String uuid = player.getUuid().toString();

        for (String internalTeamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(internalTeamName);

            boolean isOwner = teamData.getString("owner").orElse("").equals(uuid);
            boolean isMember = teamData.getListOrEmpty("members").stream()
                    .anyMatch(e -> e.asString().orElse("").equals(uuid));

            if (isOwner || isMember) {
                String tag = teamData.getString("teamTag").orElse(internalTeamName).toUpperCase();
                String colorStr = teamData.getString("tagColor").orElse("WHITE");
                Formatting tagColor;

                try {
                    tagColor = Formatting.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    tagColor = Formatting.WHITE;
                }

                // Build prefix: white [ + colored tag + white ] + space
                Text prefix = Text.literal("[")
                        .formatted(Formatting.WHITE)
                        .append(Text.literal(tag).formatted(tagColor))
                        .append(Text.literal("] ").formatted(Formatting.WHITE));

                // Add the player to a scoreboard team for overhead display
                ServerScoreboard scoreboard = server.getScoreboard();
                String teamId = toTeamId(internalTeamName); // reuse your existing toTeamId method
                Team team = scoreboard.getTeam(teamId);
                if (team == null) {
                    team = scoreboard.addTeam(teamId);
                }

                team.setPrefix(prefix);
                team.setSuffix(Text.empty()); // ensure no suffix
                team.setColor(Formatting.WHITE); // player name stays white
                scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), team);
                return;
            }
        }

        // Not in a team → remove from any existing team
        ServerScoreboard scoreboard = server.getScoreboard();
        for (Team team : scoreboard.getTeams()) {
            if (team.getPlayerList().contains(player.getNameForScoreboard())) {
                scoreboard.removeScoreHolderFromTeam(player.getNameForScoreboard(), team);
            }
        }
    }


    public static void removeAllTeams(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();

        for (Team team : scoreboard.getTeams().toArray(Team[]::new)) {
            scoreboard.removeTeam(team);
        }
    }

    public static String toTeamId(String name) {
        return name.toLowerCase().replaceAll("\\s+", "");
    }


    public static Team addTeam(
            MinecraftServer server,
            String fullName,        // "Red Team"
            String teamTag,          // "[RED]"
            Formatting color
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();
        String teamId = toTeamId(fullName);

        Team team = scoreboard.getTeam(teamId);
        if (team == null) {
            team = scoreboard.addTeam(teamId);
        }

        String tabTag = "[" + teamTag + "]";

        team.setDisplayName(Text.literal(fullName));
        team.setColor(color);

        return team;
    }

    public static void addPlayerToTeam(
            MinecraftServer server,
            ServerPlayerEntity player,
            Team team
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();

        scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), team);
    }

    public NbtCompound getData() {
        return data;
    }

    public void setData(NbtCompound data) {
        teamUtils.data = data;
    }
}
