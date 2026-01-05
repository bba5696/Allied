package com.bba.allied.teamUtils;

import com.bba.allied.data.datManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class teamUtils {
    static NbtCompound data = datManager.get().getData();

    public static final String MOD_ID = "Minecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void refreshTabForPlayer(ServerPlayerEntity player) {
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) return;

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
            String rawText = signedMessage.getContent().getString();
            Text formatted = formatTeamChat(player, rawText);

            player.getEntityWorld().getPlayers().forEach(p -> {
                p.sendMessage(formatted, false);
            });

            LOGGER.info("{}", formatted.getString());

            return false;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> teamUtils.handleFriendlyFire(entity, source));
        ServerTickEvents.END_WORLD_TICK.register(world -> updateHighlight(world.getServer()));
    }

    private static Text formatTeamChat(ServerPlayerEntity player, String originalMessage) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String playerUuid = player.getUuid().toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);

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
            prefix = scoreboardTeam.getPrefix();
        }
        Text teamName = Text.literal("[").formatted(Formatting.WHITE)
                .append(Text.literal(internalTeamName).formatted(color))
                .append(Text.literal("] ")).formatted(Formatting.WHITE);

        prefix = useTag ? prefix : teamName;

        return Text.empty()
                .append(prefix)
                .append(Text.literal("<"))
                .append(playerName)
                .append(Text.literal("> "))
                .append(Text.literal(message));
    }

    public static void rebuildTeams(
            MinecraftServer server
    ) {
        removeAllTeams(server);

        NbtCompound teamsNBT = data.getCompoundOrEmpty("teams");

        for (String internalTeamName : teamsNBT.getKeys()) {
            NbtCompound teamData = teamsNBT.getCompoundOrEmpty(internalTeamName);
            if (teamData.isEmpty()) continue;

            String colorStr = teamData
                    .getString("tagColor")
                    .orElse("WHITE");

            Formatting teamColor;
            try {
                teamColor = Formatting.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                teamColor = Formatting.WHITE;
            }

            Team scoreboardTeam = addTeam(server, internalTeamName, teamColor);

            teamData.getString("owner").ifPresent(ownerUuidStr -> {
                try {
                    UUID uuid = UUID.fromString(ownerUuidStr);
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
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

                Text prefix = Text.literal("[")
                        .formatted(Formatting.WHITE)
                        .append(Text.literal(tag).formatted(tagColor))
                        .append(Text.literal("] ").formatted(Formatting.WHITE));

                ServerScoreboard scoreboard = server.getScoreboard();
                String teamId = toTeamId(internalTeamName);
                Team team = scoreboard.getTeam(teamId);
                if (team == null) {
                    team = scoreboard.addTeam(teamId);
                }

                team.setPrefix(prefix);
                team.setSuffix(Text.empty());
                team.setColor(Formatting.WHITE);
                scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), team);
                return;
            }
        }

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
            String fullName,
            Formatting color
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();
        String teamId = toTeamId(fullName);

        Team team = scoreboard.getTeam(teamId);
        if (team == null) {
            team = scoreboard.addTeam(teamId);
        }

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

    public static boolean handleFriendlyFire(LivingEntity victim, DamageSource source) {
        if (!(source.getAttacker() instanceof ServerPlayerEntity attacker)) {
            return true;
        }

        if (!(victim instanceof ServerPlayerEntity victimPlayer)) {
            return true;
        }

        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");

        String attackerUuid = attacker.getUuid().toString();
        String victimUuid = victimPlayer.getUuid().toString();

        String attackerTeam = findPlayersTeam(teams, attackerUuid);
        String victimTeam   = findPlayersTeam(teams, victimUuid);

        if (attackerTeam == null || !attackerTeam.equals(victimTeam)) {
            return true;
        }

        NbtCompound teamData = teams.getCompoundOrEmpty(attackerTeam);

        return teamData
                .getCompoundOrEmpty("settings")
                .getBoolean("friendlyFire")
                .orElse(false);
    }

    private static String findPlayersTeam(NbtCompound teams, String uuid) {
        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);

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

    public static void updateHighlight(MinecraftServer server) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");

        Map<String, NbtCompound> uuidToTeam = new HashMap<>();
        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);
            team.getString("owner").ifPresent(owner -> uuidToTeam.put(owner, team));

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                members.getString(i).ifPresent(member -> uuidToTeam.put(member, team));
            }
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {

            NbtCompound playerTeam = uuidToTeam.get(player.getUuid().toString());

            boolean highlightEnabled = false;
            if (playerTeam != null) {
                highlightEnabled = playerTeam
                        .getCompoundOrEmpty("settings")
                        .getBoolean("highlight")
                        .orElse(false);
            }

            for (ServerPlayerEntity teammate : server.getPlayerManager().getPlayerList()) {
                if (teammate == player) continue;

                NbtCompound teammateTeam = uuidToTeam.get(teammate.getUuid().toString());

                boolean shouldGlow = highlightEnabled
                        && teammateTeam != null
                        && teammateTeam == playerTeam
                        && teammate.hasStatusEffect(StatusEffects.INVISIBILITY);

                teammate.setGlowing(shouldGlow);
            }
        }
    }
}
