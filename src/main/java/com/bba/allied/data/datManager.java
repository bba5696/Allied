package com.bba.allied.data;

import com.bba.allied.teamUtils.teamUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static com.bba.allied.data.datConfig.CreateDefault;
import static com.bba.allied.teamUtils.teamUtils.toTeamId;

public class datManager {
    public static final String MOD_ID = "allied";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    Path path = FabricLoader.getInstance().getConfigDir().resolve("allied").resolve("teams.dat");

    private static datManager INSTANCE;
    private CompoundTag data;

    private datManager(CompoundTag data) {
        this.data = data;
    }

    public static void init(CompoundTag data) {
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

    public CompoundTag getData() {
        return data;
    }

    public void save() throws IOException {
        // Write to a temp file first so a crash mid-save can't corrupt teams.dat
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.write(data, tmp);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        exportJson();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Writes config/allied/teams.json when enabled with /alliedAdmin exportJson true,
    // so websites and other tools can read the teams without parsing NBT
    public void exportJson() {
        if (!data.getCompoundOrEmpty("settings").getBooleanOr("exportJson", false)) return;

        CompoundTag teams = data.getCompoundOrEmpty("teams");
        CompoundTag names = data.getCompoundOrEmpty("playerNames");

        JsonArray teamsJson = new JsonArray();
        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            JsonObject teamJson = new JsonObject();
            teamJson.addProperty("name", teamName);
            teamJson.addProperty("tag", team.getStringOr("teamTag", ""));
            teamJson.addProperty("color", team.getStringOr("tagColor", "white").toLowerCase());

            String ownerUuid = team.getStringOr("owner", "");
            teamJson.add("owner", playerJson(ownerUuid, names));

            JsonArray membersJson = new JsonArray();
            ListTag members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                membersJson.add(playerJson(members.getString(i).orElse(""), names));
            }
            teamJson.add("members", membersJson);
            teamJson.addProperty("memberCount", members.size() + 1);

            teamsJson.add(teamJson);
        }

        JsonObject root = new JsonObject();
        root.add("teams", teamsJson);

        try {
            Path jsonPath = path.resolveSibling("teams.json");
            Path tmp = path.resolveSibling("teams.json.tmp");
            Files.writeString(tmp, GSON.toJson(root));
            Files.move(tmp, jsonPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to export teams.json", e);
        }
    }

    private static JsonObject playerJson(String uuid, CompoundTag names) {
        JsonObject player = new JsonObject();
        player.addProperty("uuid", uuid);
        player.addProperty("name", names.getStringOr(uuid, null));
        return player;
    }

    // Remembers the last known name of a player, used by the JSON export for offline players
    public void rememberName(UUID uuid, String name) throws IOException {
        CompoundTag names = data.getCompoundOrEmpty("playerNames");
        if (name.equals(names.getStringOr(uuid.toString(), null))) return;

        names.putString(uuid.toString(), name);
        data.put("playerNames", names);
        save();
    }

    // Quotes a team name so it survives being passed through a clickable command
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private boolean teamIdTaken(CompoundTag teams, String teamName, @Nullable String ignoreTeam) {
        String id = toTeamId(teamName);
        for (String existing : teams.keySet()) {
            if (existing.equals(ignoreTeam)) continue;
            if (toTeamId(existing).equals(id)) return true;
        }
        return false;
    }

    private void clearPendingFor(String playerStr) {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        for (String name : teams.keySet()) {
            CompoundTag t = teams.getCompoundOrEmpty(name);
            for (String listKey : List.of("invites", "joinRequests")) {
                ListTag list = t.getListOrEmpty(listKey);
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (playerStr.equalsIgnoreCase(list.getString(i).orElse(""))) {
                        list.remove(i);
                    }
                }
            }
        }
    }

    private void checkTeamNotFull(String teamName) throws CommandSyntaxException {
        int memberCap = data.getCompoundOrEmpty("settings").getIntOr("maxMembers", 5);
        if (getTeamMemberCount(teamName) >= memberCap) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("This team is full!")
            ).create();
        }
    }

    public boolean isOwnerOfATeam(UUID uuid) {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String ownerStr = uuid.toString();

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                return true;
            }
        }
        return false;
    }

    public boolean isMemberOfATeam(UUID uuid) {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String uuidStr = uuid.toString();

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
            ListTag members = teamData.getListOrEmpty("members");

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
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String playerId = playerUuid.toString();

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

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
                    Component.nullToEmpty("You are already in a team!")
            ).create();
        }

        CompoundTag settings = data.getCompoundOrEmpty("settings");
        int maxNameLength = settings.getIntOr("maxTeamNameLength", 16);
        int maxTagLength = settings.getIntOr("maxTeamTagLength", 4);
        if (teamName.length() > maxNameLength || teamTag.length() > maxTagLength) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("Team Name or Team Tag is too long!")
            ).create();
        }

        CompoundTag teams = data.getCompoundOrEmpty("teams");

        if (teamIdTaken(teams, teamName, null)) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("A team with this internal name already exists!")
            ).create();
        }

        for (String existingTeamName : teams.keySet()) {
            CompoundTag existingTeam = teams.getCompoundOrEmpty(existingTeamName);
            String existingTag = existingTeam.getString("teamTag").orElse("");
            if (existingTag.equalsIgnoreCase(teamTag)) {
                throw new SimpleCommandExceptionType(
                        Component.nullToEmpty("A team with this tag already exists!")
                ).create();
            }
        }

        CompoundTag team = createTeam(teamTag, ownerUUID);
        teams.put(teamName, team);

        try {
            save();
        } catch (IOException e) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("Failed to create team, please try again!")
            ).create();
        }
    }

    public void removeTeam(UUID ownerUUID) throws CommandSyntaxException, IOException {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                teams.remove(teamName);
                save();
                return;
            }
        }

        throw new SimpleCommandExceptionType(Component.nullToEmpty("You don't own a team!")).create();
    }

    public void leaveTeam(UUID ownerUUID) throws CommandSyntaxException, IOException {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);

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

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                throw new SimpleCommandExceptionType(Component.nullToEmpty("You can't leave your own team, do '/allied  disband' instead!")).create();
            }
        }

        throw new SimpleCommandExceptionType(Component.nullToEmpty("You are not in a team")).create();
    }

    public int getTeamMemberCount(String teamName) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);

        if (teamData == null || teamData.isEmpty()) {
            return 0;
        }

        ListTag members = teamData.getListOrEmpty("members");
        return members.size();
    }

    public void sendRequest(String targetTeamName, UUID playerUUID, MinecraftServer server) throws CommandSyntaxException {
        if (isInTeam(playerUUID)) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("You are in a team already!")
            ).create();
        }

        CompoundTag teams = data.getCompoundOrEmpty("teams");

        if (!teams.contains(targetTeamName)) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("Team does not exist!")
            ).create();
        }

        CompoundTag teamData = teams.getCompoundOrEmpty(targetTeamName);

        int count = getTeamMemberCount(targetTeamName);
        int memberCap = data.getCompoundOrEmpty("settings").getIntOr("maxMembers", 5);

        if (count >= memberCap) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("This team is full!")
            ).create();
        }

        boolean allowRequests = teamData
                .getCompoundOrEmpty("settings")
                .getBoolean("allowRequests")
                .orElse(false);
        if (!allowRequests) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("This team is not accepting requests right now!")
            ).create();
        }

        ListTag requests = teamData.getListOrEmpty("joinRequests");
        String playerUuidStr = playerUUID.toString();

        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).getId() == 8) {
                String existing = requests.getString(i).orElse(null);
                assert existing != null;
                if (existing.equalsIgnoreCase(playerUuidStr)) {
                    throw new SimpleCommandExceptionType(
                            Component.nullToEmpty("You have already requested to join this team!")
                    ).create();
                }
            }
        }

        requests.add(StringTag.valueOf(playerUuidStr));
        try {
            save();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save join request", e);
        }

        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        String requesterName = "";
        if (player != null) {
            requesterName = player.getGameProfile().name();
        }

        ServerPlayer owner = server.getPlayerList()
                .getPlayer(UUID.fromString(teamData.getString("owner").orElseThrow()));

        if (owner != null) {
            Component accept = Component.literal("[ACCEPT]")
                    .withStyle(ChatFormatting.GREEN)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand("/allied accept"+ " " + playerUUID))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Accept join request")))
                    );

            Component deny = Component.literal("[DENY]")
                    .withStyle(ChatFormatting.RED)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand("/allied deny" + " " + playerUUID))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Deny join request")))
                    );

            owner.sendSystemMessage(
                    Component.literal( requesterName + " wants to join your team ")
                            .append(Component.literal(targetTeamName).withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal("\n"))
                            .append(accept)
                            .append(Component.literal(" "))
                            .append(deny)
            );
        }
    }

    public void handleRequest(UUID ownerUUID, UUID requesterUUID, boolean accept) throws IOException, CommandSyntaxException {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        CompoundTag teamData = null;
        String ownedTeamName = null;

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);
            String storedOwner = team.getString("owner").orElse("");
            if (ownerStr.equalsIgnoreCase(storedOwner)) {
                teamData = team;
                ownedTeamName = teamName;
                break;
            }
        }

        if (teamData == null || teamData.isEmpty()) {
            throw new SimpleCommandExceptionType(Component.nullToEmpty("You do not own a team!")).create();
        }

        ListTag requests = teamData.getListOrEmpty("joinRequests");
        String requesterStr = requesterUUID.toString();

        int index = -1;
        for (int i = 0; i < requests.size(); i++) {
            if (requesterStr.equalsIgnoreCase(requests.getString(i).orElse(""))) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            throw new SimpleCommandExceptionType(Component.nullToEmpty("No pending request from this player!")).create();
        }

        if (accept) {
            if (isInTeam(requesterUUID)) {
                requests.remove(index);
                save();
                throw new SimpleCommandExceptionType(Component.nullToEmpty("That player has already joined another team!")).create();
            }
            checkTeamNotFull(ownedTeamName);

            ListTag members = teamData.getListOrEmpty("members");
            members.add(StringTag.valueOf(requesterStr));
            clearPendingFor(requesterStr);
        } else {
            requests.remove(index);
        }

        save();
    }

    public void sendInvite(UUID ownerUUID, UUID targetUUID, MinecraftServer server)
            throws CommandSyntaxException, IOException {

        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String ownerStr = ownerUUID.toString();

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(targetUUID);
        if (targetPlayer == null) {
            throw new SimpleCommandExceptionType(Component.literal("Target player is not online!")).create();
        }

        if (isInTeam(targetUUID)) {
            throw new SimpleCommandExceptionType(Component.literal("This player is already in a team!")).create();
        }

        String teamName = null;
        CompoundTag teamData = null;

        for (String name : teams.keySet()) {
            CompoundTag t = teams.getCompoundOrEmpty(name);
            if (ownerStr.equals(t.getString("owner").orElse(""))) {
                teamName = name;
                teamData = t;
                break;
            }
        }

        if (teamData == null) {
            throw new SimpleCommandExceptionType(Component.literal("You do not own a team!")).create();
        }

        int count = getTeamMemberCount(teamName);
        int memberCap = data.getCompoundOrEmpty("settings").getIntOr("maxMembers", 5);

        if (count >= memberCap) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("Your team is currently full, please kick someone!")
            ).create();
        }

        ListTag invites = teamData.getListOrEmpty("invites");
        String targetStr = targetUUID.toString();

        for (int i = 0; i < invites.size(); i++) {
            if (targetStr.equals(invites.getString(i).orElse(""))) {
                throw new SimpleCommandExceptionType(Component.literal("Player already invited!")).create();
            }
        }

        invites.add(StringTag.valueOf(targetStr));
        save();

        String finalTeamName = teamName;
        Component accept = Component.literal("[ACCEPT]")
                .withStyle(ChatFormatting.GREEN)
                .withStyle(s -> s.withClickEvent(
                        new ClickEvent.RunCommand("/allied invAccept " + quote(finalTeamName))));
        Component deny = Component.literal("[DENY]")
                .withStyle(ChatFormatting.RED)
                .withStyle(s -> s.withClickEvent(
                        new ClickEvent.RunCommand("/allied invDeny " + quote(finalTeamName))));

        targetPlayer.sendSystemMessage(
                Component.literal("You were invited to join team ")
                        .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal("\n"))
                        .append(accept).append(Component.literal(" ")).append(deny)
        );
    }

    public void handleInvite(UUID playerUUID, String teamName, boolean accept)
            throws IOException, CommandSyntaxException {

        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String playerStr = playerUUID.toString();

        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
        if (teamData.isEmpty()) {
            throw new SimpleCommandExceptionType(Component.literal("Team does not exist!")).create();
        }

        ListTag invites = teamData.getListOrEmpty("invites");
        int index = -1;

        for (int i = 0; i < invites.size(); i++) {
            if (playerStr.equals(invites.getString(i).orElse(""))) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            throw new SimpleCommandExceptionType(Component.literal("You do not have an invite to this team!")).create();
        }

        if (accept) {
            if (isInTeam(playerUUID)) {
                throw new SimpleCommandExceptionType(Component.literal("You are already in a team!")).create();
            }
            checkTeamNotFull(teamName);

            ListTag members = teamData.getListOrEmpty("members");
            members.add(StringTag.valueOf(playerStr));
            clearPendingFor(playerStr);
        } else {
            invites.remove(index);
        }

        save();
    }

    public List<String> getInvitedTeams(UUID playerUUID) {
        List<String> result = new ArrayList<>();

        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String playerStr = playerUUID.toString();

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
            ListTag invites = teamData.getListOrEmpty("invites");

            for (int i = 0; i < invites.size(); i++) {
                if (playerStr.equals(invites.getString(i).orElse(""))) {
                    result.add(teamName);
                    break;
                }
            }
        }

        return result;
    }

    public void handleSettings(ServerPlayer player, @Nullable String settingKey, @Nullable Boolean value) throws CommandSyntaxException, IOException {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        String teamName = null;

        UUID playerUUID = player.getUUID();
        for (String key : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(key);
            String owner = teamData.getString("owner").orElse("");
            if (owner.equalsIgnoreCase(playerUUID.toString())) {
                teamName = key;
                break;
            }
        }

        if (teamName == null) {
            throw new SimpleCommandExceptionType(Component.nullToEmpty("You don't own a team!")).create();
        }

        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
        CompoundTag settings = teamData.getCompoundOrEmpty("settings");

        if (settingKey == null || value == null) {
            showTeamSettings(player, teamName);
            return;
        }

        if (!settings.contains(settingKey)) {
            throw new SimpleCommandExceptionType(Component.nullToEmpty("Setting '" + settingKey + "' does not exist!")).create();
        }

        settings.putBoolean(settingKey, value);
        save();
    }

    public void showTeamSettings(ServerPlayer player, String teamName) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
        CompoundTag settings = teamData.getCompoundOrEmpty("settings");

        MutableComponent message = Component.literal("Team Settings for " + teamName + ":");

        for (String key : settings.keySet()) {
            boolean value = settings.getBoolean(key).orElse(false);

            Component status = Component.literal(value ? "☑" : "☒").withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
            Component enableButton = Component.literal("[ENABLE]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent.RunCommand("/allied settings" + " " + key + " " + true))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Enable " + key)))
                    );

            Component disableButton = Component.literal("[DISABLE]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(new ClickEvent.RunCommand("/allied settings" + " " + key + " " + false))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Disable " + key)))
                    );

            message.withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("\n" + key + ": "))
                    .append(status)
                    .append(Component.literal("\n "))
                    .append(enableButton)
                    .append(Component.literal(" "))
                    .append(disableButton);
        }

        player.sendSystemMessage(message);
    }

    public void handleSettingsAdmin(
            CommandSourceStack source,
            String teamName,
            @Nullable String settingKey,
            @Nullable Boolean value
    ) throws CommandSyntaxException, IOException {

        CompoundTag teams = data.getCompoundOrEmpty("teams");

        if (!teams.contains(teamName)) {
            throw new SimpleCommandExceptionType(
                    Component.literal("Team '" + teamName + "' does not exist!")
            ).create();
        }

        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
        CompoundTag settings = teamData.getCompoundOrEmpty("settings");

        if (settingKey == null || value == null) {
            showTeamSettingsAdmin(source, teamName);
            return;
        }

        if (!settings.contains(settingKey)) {
            throw new SimpleCommandExceptionType(
                    Component.literal("Setting '" + settingKey + "' does not exist!")
            ).create();
        }

        settings.putBoolean(settingKey, value);
        save();
    }

    public void showTeamSettingsAdmin(CommandSourceStack source, String teamName) {
        CompoundTag teams = data.getCompoundOrEmpty("teams");
        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
        CompoundTag settings = teamData.getCompoundOrEmpty("settings");

        MutableComponent message = Component.literal("Admin Settings for " + teamName + ":")
                .withStyle(ChatFormatting.YELLOW);

        for (String key : settings.keySet()) {
            boolean value = settings.getBoolean(key).orElse(false);

            Component status = Component.literal(value ? "☑ ENABLED" : "☒ DISABLED")
                    .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);

            Component enableButton = Component.literal("[ENABLE]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(
                                    new ClickEvent.RunCommand(
                                            "/alliedAdmin modifySettings " + quote(teamName) + " " + key + " true"
                                    )
                            )
                    );

            Component disableButton = Component.literal("[DISABLE]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(
                                    new ClickEvent.RunCommand(
                                            "/alliedAdmin modifySettings " + quote(teamName) + " " + key + " false"
                                    )
                            )
                    );

            message.append(Component.literal("\n"))
                    .append(Component.literal(key + ": "))
                    .append(status)
                    .append(Component.literal("\n "))
                    .append(enableButton)
                    .append(Component.literal(" "))
                    .append(disableButton);
        }

        source.sendSuccess(() -> message, false);
    }

    public void executeSet(ServerPlayer player, String field, String value) throws CommandSyntaxException, IOException {

        String playerUuid = player.getUUID().toString();
        CompoundTag data = datManager.get().getData();
        CompoundTag teams = data.getCompoundOrEmpty("teams");

        String foundTeamName = null;
        CompoundTag foundTeam = null;

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);
            if (team.getString("owner").orElse("").equals(playerUuid)) {
                foundTeamName = teamName;
                foundTeam = team;
                break;
            }
        }

        if (foundTeam == null) {
            throw new SimpleCommandExceptionType(
                    Component.literal("You do not own a team.")
            ).create();
        }

        if (field.equals("name") || field.equals("tag")) {
            for (String teamName : teams.keySet()) {
                CompoundTag team = teams.getCompoundOrEmpty(teamName);
                if (team == foundTeam) continue;

                if (field.equals("name")) {
                    if (toTeamId(teamName).equals(toTeamId(value))) {
                        throw new SimpleCommandExceptionType(
                                Component.literal("A team with that name already exists.")
                        ).create();
                    }
                }

                if (field.equals("tag")) {
                    String existingTag = team.getString("teamTag").orElse("");
                    if (existingTag.equalsIgnoreCase(value)) {
                        throw new SimpleCommandExceptionType(
                                Component.literal("A team with that tag already exists.")
                        ).create();
                    }
                }
            }
        }

        CompoundTag settings = data.getCompoundOrEmpty("settings");

        switch (field) {

            case "name" -> {
                int maxNameLength = settings.getIntOr("maxTeamNameLength", 16);
                if (value.length() > maxNameLength) {
                    throw new SimpleCommandExceptionType(
                            Component.nullToEmpty("Team Name is too long!")
                    ).create();
                }
                if (foundTeamName.equals(value)) {
						throw new SimpleCommandExceptionType(
								Component.literal("You are already using this Team Name.")
							).create();
                }
                teams.remove(foundTeamName);
                teams.put(value, foundTeam);

                // Keep an admin settings block attached to the team after a rename
                ListTag blocked = settings.getListOrEmpty("blockTeamsSettings");
                for (int i = 0; i < blocked.size(); i++) {
                    if (foundTeamName.equalsIgnoreCase(blocked.getString(i).orElse(""))) {
                        blocked.set(i, StringTag.valueOf(value));
                    }
                }
            }

            case "tag" -> {
                int maxTagLength = settings.getIntOr("maxTeamTagLength", 4);
                if (value.length() > maxTagLength) {
                    throw new SimpleCommandExceptionType(
                            Component.nullToEmpty("Team Tag is too long!")
                    ).create();
                }
                foundTeam.putString("teamTag", value);
            }

            case "color" -> {
                try {
                    TeamColor c = TeamColor.valueOf(value.toUpperCase());
                    foundTeam.putString("tagColor", c.getSerializedName());
                } catch (Exception e) {
                    throw new SimpleCommandExceptionType(
                            Component.literal("Invalid color.")
                    ).create();
                }
            }

            default -> throw new SimpleCommandExceptionType(
                    Component.literal("Invalid field. Use name, tag, or color.")
            ).create();
        }

        datManager.get().save();

    }

    public void kickMember(ServerPlayer owner, ServerPlayer target) throws IOException {
        if (owner == null || target == null) return;

        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String ownerStr = owner.getUUID().toString();
        String targetStr = target.getUUID().toString();

        CompoundTag teamData = null;
        for (String tName : teams.keySet()) {
            CompoundTag t = teams.getCompoundOrEmpty(tName);
            if (ownerStr.equals(t.getString("owner").orElse(""))) {
                teamData = t;
                break;
            }
        }

        if (teamData == null) {
            owner.sendSystemMessage(Component.literal("You do not own a team!").withStyle(ChatFormatting.RED));
            return;
        }

        ListTag members = teamData.getListOrEmpty("members");
        boolean removed = false;
        for (int i = 0; i < members.size(); i++) {
            if (targetStr.equals(members.getString(i).orElse(""))) {
                members.remove(i);
                removed = true;
                break;
            }
        }

        if (!removed) {
            owner.sendSystemMessage(Component.literal(target.getGameProfile().name() + " is not in your team!")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        datManager.get().save();
        owner.sendSystemMessage(
                Component.literal("Removed ")
                        .append(Component.literal(target.getGameProfile().name()).withStyle(ChatFormatting.RED))
                        .append(Component.literal(" from your team."))
        );

    }

    public MutableComponent getTeamInfo(MinecraftServer server, String teamName) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        CompoundTag teamData = teams.getCompoundOrEmpty(teamName);

        if (teamData == null || teamData.isEmpty()) {
            return Component.literal("Team not found!").withStyle(ChatFormatting.RED);
        }

        MutableComponent info = Component.literal("Team Name: ").withStyle(ChatFormatting.GOLD);
        info.append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW)).append(Component.literal("\n"));

        String tag = teamData.getString("teamTag").orElse("No Tag");
        info.append(Component.literal("Team Tag: ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(tag).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n"));

        String ownerUUIDStr = teamData.getString("owner").orElse("");
        ServerPlayer ownerPlayer = null;
        try {
            ownerPlayer = server.getPlayerList().getPlayer(UUID.fromString(ownerUUIDStr));
        } catch (IllegalArgumentException ignored) {}
        MutableComponent ownerText = (ownerPlayer != null)
                ? Component.literal(ownerPlayer.getGameProfile().name()).withStyle(ChatFormatting.GREEN)
                : Component.literal("Offline").withStyle(ChatFormatting.RED);

        info.append(Component.literal("Owner: ").withStyle(ChatFormatting.GOLD)).append(ownerText).append(Component.literal("\n"));

        ListTag members = teamData.getListOrEmpty("members");
        int offlineCount = 0;
        MutableComponent membersText = Component.literal("");

        for (int i = 0; i < members.size(); i++) {
            String memberUUIDStr = members.getString(i).orElse("");
            ServerPlayer member = null;
            try {
                member = server.getPlayerList().getPlayer(UUID.fromString(memberUUIDStr));
            } catch (IllegalArgumentException ignored) {}

            if (i > 0) membersText.append(Component.literal(", "));

            if (member != null) {
                membersText.append(Component.literal(member.getGameProfile().name()).withStyle(ChatFormatting.GREEN));
            } else {
                offlineCount++;
            }
        }

        if (offlineCount > 0) {
            if (!membersText.getString().isEmpty()) membersText.append(Component.literal(", "));
            membersText.append(Component.literal("(" + offlineCount + ") Offline").withStyle(ChatFormatting.RED));
        }

        info.append(Component.literal("Members: ").withStyle(ChatFormatting.GOLD)).append(membersText);

        return info;
    }

    public static CompoundTag createTeam(String teamTag, UUID ownerUUID) {
        CompoundTag teamData = new CompoundTag();

        teamData.putString("teamTag", teamTag);
        teamData.putString("tagColor", "WHITE");

        teamData.putString("owner", ownerUUID.toString());

        teamData.put("members", new ListTag());

        teamData.put("joinRequests", new ListTag());
        teamData.put("invites", new ListTag());

        CompoundTag settings = new CompoundTag();

        settings.putBoolean("friendlyFire", false);
        settings.putBoolean("highlight", false);
        settings.putBoolean("allowRequests", true);
        settings.putBoolean("chatUseTag", true);
        settings.putBoolean("tabUseTag", true);

        teamData.put("settings", settings);

        return teamData;
    }
}

