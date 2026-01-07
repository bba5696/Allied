package com.bba.allied.data;

import com.bba.allied.teamUtils.teamUtils;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static com.bba.allied.data.datConfig.CreateDefault;
import static com.bba.allied.teamUtils.teamUtils.toTeamId;

public class datManager {
    public static final String MOD_ID = "allied";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    Path path = FabricLoader.getInstance().getConfigDir().resolve("allied").resolve("teams.dat");

    private static datManager INSTANCE;
    private NbtCompound data;

    private datManager(NbtCompound data) {
        this.data = data;
    }

    public static void init(NbtCompound data) {
        INSTANCE = new datManager(data);
    }

    public void resetData(MinecraftServer server) throws IOException {
        data = CreateDefault();
        save();
        datConfig.InitialiseDatFolder();

        LOGGER.info("ALLIED MOD DATA HAS BEEN RESET!");

        teamUtils.rebuildTeams(server);
    }

    public static datManager get() {
        if (INSTANCE == null) throw new IllegalStateException("datManager not initialized!");
        return INSTANCE;
    }

    public NbtCompound getData() {
        return data;
    }

    public void save() throws IOException {
        NbtIo.write(data, path);
    }

    public boolean isOwnerOfATeam(UUID uuid) {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String ownerStr = uuid.toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                return true;
            }
        }
        return false;
    }

    public boolean isMemberOfATeam(UUID uuid) {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String uuidStr = uuid.toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
            NbtList members = teamData.getListOrEmpty("members");

            for (int i = 0; i < members.size(); i++) {
                if (uuidStr.equalsIgnoreCase(members.getString(i).orElse(null))) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isInTeam(UUID uuid) {
        return isOwnerOfATeam(uuid) || isMemberOfATeam(uuid);
    }

    public String getTeam(UUID playerUuid) {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String playerId = playerUuid.toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);

            if (team.getString("owner").orElse("").equalsIgnoreCase(playerId)) {
                return teamName;
            }

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                if (members.getString(i).orElse("").equalsIgnoreCase(playerId)) {
                    return teamName;
                }
            }
        }

        return null;
    }

    public void addTeam(String teamName, String teamTag, UUID ownerUUID) throws CommandSyntaxException  {
        if (isInTeam(ownerUUID)) {
            throw new SimpleCommandExceptionType(
                    Text.of("You are already in a team!")
            ).create();
        }

        if (teamName.length() > 16 || teamTag.length() > 4) {
            throw new SimpleCommandExceptionType(
                    Text.of("Team Name or Team Tag is too long!")
            ).create();
        }

        NbtCompound teams = data.getCompoundOrEmpty("teams");

        if (teams.contains(toTeamId(teamName))) {
            throw new SimpleCommandExceptionType(
                    Text.of("A team with this internal name already exists!")
            ).create();
        }

        for (String existingTeamName : teams.getKeys()) {
            NbtCompound existingTeam = teams.getCompoundOrEmpty(existingTeamName);
            String existingTag = existingTeam.getString("teamTag").orElse("");
            if (existingTag.equalsIgnoreCase(teamTag)) {
                throw new SimpleCommandExceptionType(
                        Text.of("A team with this tag already exists!")
                ).create();
            }
        }

        NbtCompound team = createTeam(teamTag, ownerUUID);
        teams.put(teamName, team);

        try {
            save();
        } catch (IOException e) {
            throw new SimpleCommandExceptionType(
                    Text.of("Failed to create team, please try again!")
            ).create();
        }
    }

    public void removeTeam(UUID ownerUUID) throws CommandSyntaxException, IOException {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                teams.remove(teamName);
                save();
                return;
            }
        }

        throw new SimpleCommandExceptionType(Text.of("You don't own a team!")).create();
    }

    public void leaveTeam(UUID ownerUUID) throws CommandSyntaxException, IOException {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(teamName);

            var members = teamData.getListOrEmpty("members");

            for (int i = 0; i < members.size(); i++) {
                String memberUuid = members.getString(i).orElse("");

                if (ownerStr.equalsIgnoreCase(memberUuid)) {
                    members.remove(i);
                    save();
                    return;
                }
            }
        }

        for (String teamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                throw new SimpleCommandExceptionType(Text.of("You can't leave your own team, do '/allied  disband' instead!")).create();
            }
        }

        throw new SimpleCommandExceptionType(Text.of("You are not in a team")).create();
    }

    public int getTeamMemberCount(String teamName) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        NbtCompound teamData = teams.getCompoundOrEmpty(teamName);

        if (teamData == null || teamData.isEmpty()) {
            return 0;
        }

        NbtList members = teamData.getListOrEmpty("members");
        return members.size();
    }

    public void sendRequest(String targetTeamName, UUID playerUUID, MinecraftServer server) throws CommandSyntaxException {
        if (isInTeam(playerUUID)) {
            throw new SimpleCommandExceptionType(
                    Text.of("You are in a team already!")
            ).create();
        }

        NbtCompound teams = data.getCompoundOrEmpty("teams");

        if (!teams.contains(targetTeamName)) {
            throw new SimpleCommandExceptionType(
                    Text.of("Team does not exist!")
            ).create();
        }

        NbtCompound teamData = teams.getCompoundOrEmpty(targetTeamName);

        int count = getTeamMemberCount(targetTeamName);
        int memberCap = data.getCompoundOrEmpty("settings").getInt("maxMembers", 5);

        if (count >= memberCap) {
            throw new SimpleCommandExceptionType(
                    Text.of("This team is full!")
            ).create();
        }

        boolean allowRequests = teamData
                .getCompoundOrEmpty("settings")
                .getBoolean("allowRequests")
                .orElse(false);
        if (!allowRequests) {
            throw new SimpleCommandExceptionType(
                    Text.of("This team is not accepting requests right now!")
            ).create();
        }

        NbtList requests = teamData.getListOrEmpty("joinRequests");
        String playerUuidStr = playerUUID.toString();

        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).getType() == 8) {
                String existing = requests.getString(i).orElse(null);
                assert existing != null;
                if (existing.equalsIgnoreCase(playerUuidStr)) {
                    throw new SimpleCommandExceptionType(
                            Text.of("You have already requested to join this team!")
                    ).create();
                }
            }
        }

        requests.add(NbtString.of(playerUuidStr));
        try {
            save();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save join request", e);
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);
        String requesterName = "";
        if (player != null) {
            requesterName = player.getGameProfile().name();
        }

        ServerPlayerEntity owner = server.getPlayerManager()
                .getPlayer(UUID.fromString(teamData.getString("owner").orElseThrow()));

        if (owner != null) {
            Text accept = Text.literal("[ACCEPT]")
                    .formatted(Formatting.GREEN)
                    .styled(style -> style
                            .withClickEvent(new ClickEvent.RunCommand("/allied accept"+ " " + playerUUID))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("Accept join request")))
                    );

            Text deny = Text.literal("[DENY]")
                    .formatted(Formatting.RED)
                    .styled(style -> style
                            .withClickEvent(new ClickEvent.RunCommand("/allied deny" + " " + playerUUID))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("Deny join request")))
                    );

            owner.sendMessage(
                    Text.literal( requesterName + " wants to join your team ")
                            .append(Text.literal(targetTeamName).formatted(Formatting.YELLOW))
                            .append(Text.literal("\n"))
                            .append(accept)
                            .append(Text.literal(" "))
                            .append(deny),
                    false
            );
        }
    }

    public void handleRequest(UUID ownerUUID, UUID requesterUUID, boolean accept) throws IOException, CommandSyntaxException {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        NbtCompound teamData = null;

        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);
            String storedOwner = team.getString("owner").orElseThrow();
            if (ownerStr.equalsIgnoreCase(storedOwner)) {
                teamData = team;
                break;
            }
        }

        if (teamData == null || teamData.isEmpty()) {
            throw new SimpleCommandExceptionType(Text.of("You do not own a team!")).create();
        }

        NbtList requests = teamData.getListOrEmpty("joinRequests");
        String requesterStr = requesterUUID.toString();

        boolean found = false;
        for (int i = 0; i < requests.size(); i++) {
            String requestUUID = requests.getString(i).orElseThrow( );
            if (requesterStr.equals(requestUUID)) {
                found = true;
                requests.remove(i);
                break;
            }
        }

        if (!found) {
            throw new SimpleCommandExceptionType(Text.of("No pending request from this player!")).create();
        }

        if (accept) {
            NbtList members = teamData.getListOrEmpty("members");
            members.add(NbtString.of(requesterStr));
        }

        save();
    }

    public void sendInvite(UUID ownerUUID, UUID targetUUID, MinecraftServer server)
            throws CommandSyntaxException, IOException {

        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        ServerPlayerEntity targetPlayer = server.getPlayerManager().getPlayer(targetUUID);
        if (targetPlayer == null) {
            throw new SimpleCommandExceptionType(Text.literal("Target player is not online!")).create();
        }

        if (isInTeam(targetUUID)) {
            throw new SimpleCommandExceptionType(Text.literal("This player is already in a team!")).create();
        }

        String teamName = null;
        NbtCompound teamData = null;

        for (String name : teams.getKeys()) {
            NbtCompound t = teams.getCompoundOrEmpty(name);
            if (ownerStr.equals(t.getString("owner").orElse(""))) {
                teamName = name;
                teamData = t;
                break;
            }
        }

        int count = getTeamMemberCount(teamName);
        int memberCap = data.getCompoundOrEmpty("settings").getInt("maxMembers", 5);

        if (count >= memberCap) {
            throw new SimpleCommandExceptionType(
                    Text.of("Your team is currently full, please kick someone!")
            ).create();
        }

        if (teamData == null) {
            throw new SimpleCommandExceptionType(Text.literal("You do not own a team!")).create();
        }

        NbtList invites = teamData.getListOrEmpty("invites");
        String targetStr = targetUUID.toString();

        for (int i = 0; i < invites.size(); i++) {
            if (targetStr.equals(invites.getString(i).orElse(""))) {
                throw new SimpleCommandExceptionType(Text.literal("Player already invited!")).create();
            }
        }

        invites.add(NbtString.of(targetStr));
        save();

        String finalTeamName = teamName;
        Text accept = Text.literal("[ACCEPT]")
                .formatted(Formatting.GREEN)
                .styled(s -> s.withClickEvent(
                        new ClickEvent.RunCommand("/allied invAccept " + finalTeamName)));
        Text deny = Text.literal("[DENY]")
                .formatted(Formatting.RED)
                .styled(s -> s.withClickEvent(
                        new ClickEvent.RunCommand("/allied invDeny " + finalTeamName)));

        targetPlayer.sendMessage(
                Text.literal("You were invited to join team ")
                        .append(Text.literal(teamName).formatted(Formatting.YELLOW))
                        .append(Text.literal("\n"))
                        .append(accept).append(Text.literal(" ")).append(deny),
                false
        );
    }

    public void handleInvite(UUID playerUUID, String teamName, boolean accept)
            throws IOException, CommandSyntaxException {

        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String playerStr = playerUUID.toString();

        NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
        if (teamData.isEmpty()) {
            throw new SimpleCommandExceptionType(Text.literal("Team does not exist!")).create();
        }

        NbtList invites = teamData.getListOrEmpty("invites");
        boolean found = false;

        for (int i = 0; i < invites.size(); i++) {
            if (playerStr.equals(invites.getString(i).orElse(""))) {
                invites.remove(i);
                found = true;
                break;
            }
        }

        if (!found) {
            throw new SimpleCommandExceptionType(Text.literal("You do not have an invite to this team!")).create();
        }

        if (accept) {
            if (isInTeam(playerUUID)) {
                throw new SimpleCommandExceptionType(Text.literal("You are already in a team!")).create();
            }

            NbtList members = teamData.getListOrEmpty("members");
            members.add(NbtString.of(playerStr));

            for (String name : teams.getKeys()) {
                NbtCompound t = teams.getCompoundOrEmpty(name);
                NbtList otherInvites = t.getListOrEmpty("invites");

                for (int i = otherInvites.size() - 1; i >= 0; i--) {
                    if (playerStr.equals(otherInvites.getString(i).orElse(""))) {
                        otherInvites.remove(i);
                    }
                }
            }
        }

        save();
    }

    public List<String> getInvitedTeams(UUID playerUUID) {
        List<String> result = new ArrayList<>();

        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String playerStr = playerUUID.toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
            NbtList invites = teamData.getListOrEmpty("invites");

            for (int i = 0; i < invites.size(); i++) {
                if (playerStr.equals(invites.getString(i).orElse(""))) {
                    result.add(teamName);
                    break;
                }
            }
        }

        return result;
    }

    public void handleSettings(ServerPlayerEntity player, @Nullable String settingKey, @Nullable Boolean value) throws CommandSyntaxException, IOException {
        NbtCompound teams = data.getCompoundOrEmpty("teams");
        String teamName = null;

        UUID playerUUID = player.getUuid();
        for (String key : teams.getKeys()) {
            NbtCompound teamData = teams.getCompoundOrEmpty(key);
            String owner = teamData.getString("owner").orElse("");
            if (owner.equalsIgnoreCase(playerUUID.toString())) {
                teamName = key;
                break;
            }
        }

        if (teamName == null) {
            throw new SimpleCommandExceptionType(Text.of("You don't own a team!")).create();
        }

        NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
        NbtCompound settings = teamData.getCompoundOrEmpty("settings");

        if (settingKey == null || value == null) {
            showTeamSettings(player, teamName);
            return;
        }

        if (!settings.contains(settingKey)) {
            throw new SimpleCommandExceptionType(Text.of("Setting '" + settingKey + "' does not exist!")).create();
        }

        settings.putBoolean(settingKey, value);
        save();
    }

    public void showTeamSettings(ServerPlayerEntity player, String teamName) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        NbtCompound teamData = teams.getCompoundOrEmpty(teamName);
        NbtCompound settings = teamData.getCompoundOrEmpty("settings");

        MutableText message = Text.literal("Team Settings for " + teamName + ":");

        for (String key : settings.getKeys()) {
            boolean value = settings.getBoolean(key).orElse(false);

            Text status = Text.literal(value ? "☑" : "☒").formatted(value ? Formatting.GREEN : Formatting.RED);
            Text enableButton = Text.literal("[ENABLE]")
                    .styled(style -> style
                            .withColor(Formatting.GREEN)
                            .withClickEvent(new ClickEvent.RunCommand("/allied settings" + " " + key + " " + true))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("Enable " + key)))
                    );

            Text disableButton = Text.literal("[DISABLE]")
                    .styled(style -> style
                            .withColor(Formatting.RED)
                            .withClickEvent(new ClickEvent.RunCommand("/allied settings" + " " + key + " " + false))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("Disable " + key)))
                    );

            message.formatted(Formatting.YELLOW)
                    .append(Text.literal("\n" + key + ": "))
                    .append(status)
                    .append(Text.literal("\n "))
                    .append(enableButton)
                    .append(Text.literal(" "))
                    .append(disableButton);
        }

        player.sendMessage(message, false);
    }

    public void executeSet(ServerPlayerEntity player, String field, String value) throws CommandSyntaxException, IOException {

        String playerUuid = player.getUuid().toString();

        NbtCompound data = datManager.get().getData();
        NbtCompound teams = data.getCompoundOrEmpty("teams");

        String foundTeamName = null;
        NbtCompound foundTeam = null;

        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);
            if (team.getString("owner").orElse("").equals(playerUuid)) {
                foundTeamName = teamName;
                foundTeam = team;
                break;
            }
        }

        if (foundTeam == null) {
            throw new SimpleCommandExceptionType(
                    Text.literal("You do not own a team.")
            ).create();
        }

        if (field.equals("name") || field.equals("tag")) {
            for (String teamName : teams.getKeys()) {
                NbtCompound team = teams.getCompoundOrEmpty(teamName);
                if (team == foundTeam) continue;

                if (field.equals("name")) {
                    if (teamName.equalsIgnoreCase(value)) {
                        throw new SimpleCommandExceptionType(
                                Text.literal("A team with that name already exists.")
                        ).create();
                    }
                }

                if (field.equals("tag")) {
                    String existingTag = team.getString("teamTag").orElse("");
                    if (existingTag.equalsIgnoreCase(value)) {
                        throw new SimpleCommandExceptionType(
                                Text.literal("A team with that tag already exists.")
                        ).create();
                    }
                }
            }
        }

        switch (field) {

            case "name" -> {
                teams.put(value, foundTeam);
                teams.remove(foundTeamName);
            }

            case "tag" -> foundTeam.putString("teamTag", value);

            case "color" -> {
                try {
                    Formatting f = Formatting.valueOf(value.toUpperCase());
                    if (!f.isColor()) throw new IllegalArgumentException();
                    foundTeam.putString("tagColor", f.getName());
                } catch (Exception e) {
                    throw new SimpleCommandExceptionType(
                            Text.literal("Invalid color.")
                    ).create();
                }
            }

            default -> throw new SimpleCommandExceptionType(
                    Text.literal("Invalid field. Use name, tag, or color.")
            ).create();
        }

        datManager.get().save();

    }

    public void kickMember(ServerPlayerEntity owner, ServerPlayerEntity target) throws IOException {
        if (owner == null || target == null) return;

        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String ownerStr = owner.getUuid().toString();
        String targetStr = target.getUuid().toString();

        NbtCompound teamData = null;
        for (String tName : teams.getKeys()) {
            NbtCompound t = teams.getCompoundOrEmpty(tName);
            if (ownerStr.equals(t.getString("owner").orElse(""))) {
                teamData = t;
                break;
            }
        }

        if (teamData == null) {
            owner.sendMessage(Text.literal("You do not own a team!").formatted(Formatting.RED), false);
            return;
        }

        NbtList members = teamData.getListOrEmpty("members");
        boolean removed = false;
        for (int i = 0; i < members.size(); i++) {
            if (targetStr.equals(members.getString(i).orElse(""))) {
                members.remove(i);
                removed = true;
                break;
            }
        }

        if (!removed) {
            owner.sendMessage(Text.literal(target.getGameProfile().name() + " is not in your team!")
                    .formatted(Formatting.RED), false);
            return;
        }

        datManager.get().save();
        owner.sendMessage(
                Text.literal("Removed ")
                        .append(Text.literal(target.getGameProfile().name()).formatted(Formatting.RED))
                        .append(Text.literal(" from your team.")),
                false
        );

    }

    public MutableText getTeamInfo(MinecraftServer server, String teamName) {
        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        NbtCompound teamData = teams.getCompoundOrEmpty(teamName);

        if (teamData == null || teamData.isEmpty()) {
            return Text.literal("Team not found!").formatted(Formatting.RED);
        }

        MutableText info = Text.literal("Team Name: ").formatted(Formatting.GOLD);
        info.append(Text.literal(teamName).formatted(Formatting.YELLOW)).append(Text.literal("\n"));

        String tag = teamData.getString("teamTag").orElse("No Tag");
        info.append(Text.literal("Team Tag: ").formatted(Formatting.GOLD))
                .append(Text.literal(tag).formatted(Formatting.AQUA))
                .append(Text.literal("\n"));

        String ownerUUIDStr = teamData.getString("owner").orElse("");
        ServerPlayerEntity ownerPlayer = null;
        try {
            ownerPlayer = server.getPlayerManager().getPlayer(UUID.fromString(ownerUUIDStr));
        } catch (IllegalArgumentException ignored) {}
        MutableText ownerText = (ownerPlayer != null)
                ? Text.literal(ownerPlayer.getGameProfile().name()).formatted(Formatting.GREEN)
                : Text.literal("Offline").formatted(Formatting.RED);

        info.append(Text.literal("Owner: ").formatted(Formatting.GOLD)).append(ownerText).append(Text.literal("\n"));

        NbtList members = teamData.getListOrEmpty("members");
        int offlineCount = 0;
        MutableText membersText = Text.literal("");

        for (int i = 0; i < members.size(); i++) {
            String memberUUIDStr = members.getString(i).orElse("");
            ServerPlayerEntity member = null;
            try {
                member = server.getPlayerManager().getPlayer(UUID.fromString(memberUUIDStr));
            } catch (IllegalArgumentException ignored) {}

            if (i > 0) membersText.append(Text.literal(", "));

            if (member != null) {
                membersText.append(Text.literal(member.getGameProfile().name()).formatted(Formatting.GREEN));
            } else {
                offlineCount++;
            }
        }

        if (offlineCount > 0) {
            if (!membersText.getString().isEmpty()) membersText.append(Text.literal(", "));
            membersText.append(Text.literal("(" + offlineCount + ") Offline").formatted(Formatting.RED));
        }

        info.append(Text.literal("Members: ").formatted(Formatting.GOLD)).append(membersText);

        return info;
    }

    public static NbtCompound createTeam(String teamTag, UUID ownerUUID) {
        NbtCompound teamData = new NbtCompound();

        teamData.putString("teamTag", teamTag);
        teamData.putString("tagColor", "WHITE");

        teamData.putString("owner", ownerUUID.toString());

        teamData.put("members", new NbtList());

        teamData.put("joinRequests", new NbtList());
        teamData.put("invites", new NbtList());

        NbtCompound settings = new NbtCompound();

        settings.putBoolean("friendlyFire", false);
        settings.putBoolean("highlight", false);
        settings.putBoolean("allowRequests", true);
        settings.putBoolean("chatUseTag", true);

        teamData.put("settings", settings);

        return teamData;
    }
}

