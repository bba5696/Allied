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
import net.minecraft.server.players.NameAndId;
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
        INSTANCE.migrate();
    }

    // Fills in fields added in newer versions so older teams.dat files keep working
    private void migrate() {
        boolean changed = false;
        CompoundTag teams = data.getCompoundOrEmpty("teams");

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            if (!team.contains("id")) {
                team.putString("id", UUID.randomUUID().toString());
                changed = true;
            }
            if (!team.contains("officers")) {
                team.put("officers", new ListTag());
                changed = true;
            }
            if (!team.contains("marks")) {
                team.put("marks", new CompoundTag());
                changed = true;
            }
            if (!team.contains("storageEnabled")) {
                team.putBoolean("storageEnabled", data.getCompoundOrEmpty("settings").getBooleanOr("storageDefault", false));
                changed = true;
            }

            CompoundTag settings = team.getCompoundOrEmpty("settings");
            if (!settings.contains("deathCoords")) {
                settings.putBoolean("deathCoords", true);
                team.put("settings", settings);
                changed = true;
            }
        }

        if (changed) {
            try {
                save();
            } catch (IOException e) {
                LOGGER.error("Failed to save migrated team data", e);
            }
        }
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

    public enum Role { OWNER, OFFICER, MEMBER }

    public CompoundTag getTeamData(String teamName) {
        return data.getCompoundOrEmpty("teams").getCompoundOrEmpty(teamName);
    }

    // Officers are also kept in the members list, so member counts and membership checks stay the same
    public @Nullable Role getRole(CompoundTag team, UUID uuid) {
        String id = uuid.toString();
        if (id.equalsIgnoreCase(team.getStringOr("owner", ""))) return Role.OWNER;
        if (containsUuid(team.getListOrEmpty("officers"), id)) return Role.OFFICER;
        if (containsUuid(team.getListOrEmpty("members"), id)) return Role.MEMBER;
        return null;
    }

    public static boolean containsUuid(ListTag list, String uuid) {
        for (int i = 0; i < list.size(); i++) {
            if (uuid.equalsIgnoreCase(list.getString(i).orElse(""))) return true;
        }
        return false;
    }

    public static boolean removeUuid(ListTag list, String uuid) {
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (uuid.equalsIgnoreCase(list.getString(i).orElse(""))) {
                list.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    public String requireTeam(UUID uuid) throws CommandSyntaxException {
        String teamName = getTeam(uuid);
        if (teamName == null) {
            throw new SimpleCommandExceptionType(Component.literal("You are not in a team!")).create();
        }
        return teamName;
    }

    // Team of a player who is allowed to manage members (owner or officer)
    public String requireManagedTeam(UUID uuid) throws CommandSyntaxException {
        String teamName = requireTeam(uuid);
        Role role = getRole(getTeamData(teamName), uuid);
        if (role != Role.OWNER && role != Role.OFFICER) {
            throw new SimpleCommandExceptionType(Component.literal("Only the team owner and officers can do that!")).create();
        }
        return teamName;
    }

    public String requireOwnedTeam(UUID uuid) throws CommandSyntaxException {
        String teamName = requireTeam(uuid);
        if (getRole(getTeamData(teamName), uuid) != Role.OWNER) {
            throw new SimpleCommandExceptionType(Component.literal("Only the team owner can do that!")).create();
        }
        return teamName;
    }

    // Owner first, then members
    public List<UUID> getTeamPlayers(CompoundTag team) {
        List<UUID> players = new ArrayList<>();
        team.getString("owner").ifPresent(owner -> {
            try { players.add(UUID.fromString(owner)); } catch (IllegalArgumentException ignored) {}
        });
        ListTag members = team.getListOrEmpty("members");
        for (int i = 0; i < members.size(); i++) {
            members.getString(i).ifPresent(member -> {
                try { players.add(UUID.fromString(member)); } catch (IllegalArgumentException ignored) {}
            });
        }
        return players;
    }

    public void messageTeam(MinecraftServer server, CompoundTag team, Component message) {
        for (UUID uuid : getTeamPlayers(team)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) player.sendSystemMessage(message);
        }
    }

    // Returns true if at least one owner/officer was online to receive it
    public boolean messageManagers(MinecraftServer server, CompoundTag team, Component message) {
        boolean sent = false;
        for (UUID uuid : getTeamPlayers(team)) {
            Role role = getRole(team, uuid);
            if (role != Role.OWNER && role != Role.OFFICER) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.sendSystemMessage(message);
                sent = true;
            }
        }
        return sent;
    }

    // Finds a player by name, including offline players who have joined this server before
    public UUID resolvePlayer(MinecraftServer server, String name) throws CommandSyntaxException {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {}

        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();

        CompoundTag names = data.getCompoundOrEmpty("playerNames");
        for (String uuid : names.keySet()) {
            if (name.equalsIgnoreCase(names.getStringOr(uuid, ""))) {
                try { return UUID.fromString(uuid); } catch (IllegalArgumentException ignored) {}
            }
        }

        Optional<NameAndId> cached = server.services().nameToIdCache().get(name);
        if (cached.isPresent()) return cached.get().id();

        throw new SimpleCommandExceptionType(
                Component.literal("Unknown player '" + name + "'. They need to have joined this server before.")
        ).create();
    }

    public String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().name();

        String known = data.getCompoundOrEmpty("playerNames").getStringOr(uuid.toString(), null);
        if (known != null) return known;

        return server.services().nameToIdCache().get(uuid).map(NameAndId::name).orElse(uuid.toString().substring(0, 8));
    }

    public static Component inviteMessage(String teamName) {
        Component accept = Component.literal("[ACCEPT]")
                .withStyle(ChatFormatting.GREEN)
                .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/allied invAccept " + quote(teamName))));
        Component deny = Component.literal("[DENY]")
                .withStyle(ChatFormatting.RED)
                .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/allied invDeny " + quote(teamName))));

        return Component.literal("You were invited to join team ")
                .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n"))
                .append(accept).append(Component.literal(" ")).append(deny);
    }

    public static Component requestMessage(String requesterName, UUID requesterUUID, String teamName) {
        Component accept = Component.literal("[ACCEPT]")
                .withStyle(ChatFormatting.GREEN)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/allied accept " + requesterUUID))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Accept join request")))
                );
        Component deny = Component.literal("[DENY]")
                .withStyle(ChatFormatting.RED)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/allied deny " + requesterUUID))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Deny join request")))
                );

        return Component.literal(requesterName + " wants to join your team ")
                .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n"))
                .append(accept)
                .append(Component.literal(" "))
                .append(deny);
    }

    // Invites and join requests are stored with the team, so they wait for players who were offline
    public void sendLoginNotices(ServerPlayer player, MinecraftServer server) {
        for (String teamName : getInvitedTeams(player.getUUID())) {
            player.sendSystemMessage(inviteMessage(teamName));
        }

        String teamName = getTeam(player.getUUID());
        if (teamName == null) return;

        CompoundTag team = getTeamData(teamName);
        Role role = getRole(team, player.getUUID());
        if (role != Role.OWNER && role != Role.OFFICER) return;

        ListTag requests = team.getListOrEmpty("joinRequests");
        for (int i = 0; i < requests.size(); i++) {
            String requester = requests.getString(i).orElse("");
            try {
                UUID requesterUUID = UUID.fromString(requester);
                player.sendSystemMessage(requestMessage(nameOf(server, requesterUUID), requesterUUID, teamName));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void promote(UUID ownerUUID, UUID target) throws CommandSyntaxException, IOException {
        String teamName = requireOwnedTeam(ownerUUID);
        CompoundTag team = getTeamData(teamName);
        Role role = getRole(team, target);

        if (role == Role.OFFICER) {
            throw new SimpleCommandExceptionType(Component.literal("That player is already an officer!")).create();
        }
        if (role != Role.MEMBER) {
            throw new SimpleCommandExceptionType(Component.literal("That player is not a member of your team!")).create();
        }

        ListTag officers = team.getListOrEmpty("officers");
        officers.add(StringTag.valueOf(target.toString()));
        team.put("officers", officers);
        save();
    }

    public void demote(UUID ownerUUID, UUID target) throws CommandSyntaxException, IOException {
        String teamName = requireOwnedTeam(ownerUUID);
        CompoundTag team = getTeamData(teamName);

        if (getRole(team, target) != Role.OFFICER) {
            throw new SimpleCommandExceptionType(Component.literal("That player is not an officer!")).create();
        }

        removeUuid(team.getListOrEmpty("officers"), target.toString());
        save();
    }

    // The old owner stays in the team as an officer
    public void transferOwnership(String teamName, UUID newOwner) throws CommandSyntaxException, IOException {
        CompoundTag team = getTeamData(teamName);
        if (team.isEmpty()) {
            throw new SimpleCommandExceptionType(Component.literal("Team '" + teamName + "' does not exist!")).create();
        }

        Role role = getRole(team, newOwner);
        if (role == Role.OWNER) {
            throw new SimpleCommandExceptionType(Component.literal("That player already owns the team!")).create();
        }
        if (role == null) {
            throw new SimpleCommandExceptionType(Component.literal("The new owner must be a member of the team!")).create();
        }

        String oldOwner = team.getStringOr("owner", "");
        ListTag members = team.getListOrEmpty("members");
        ListTag officers = team.getListOrEmpty("officers");

        removeUuid(members, newOwner.toString());
        removeUuid(officers, newOwner.toString());

        if (!oldOwner.isEmpty()) {
            members.add(StringTag.valueOf(oldOwner));
            officers.add(StringTag.valueOf(oldOwner));
        }

        team.putString("owner", newOwner.toString());
        save();
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
        team.putBoolean("storageEnabled", settings.getBooleanOr("storageDefault", false));
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
                    removeUuid(teamData.getListOrEmpty("officers"), ownerStr);
                    save();
                    return;
                }
            }
        }

        for (String teamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(teamName);
            Optional<String> storedOwner = teamData.getString("owner");

            if (ownerStr.equalsIgnoreCase(storedOwner.orElse(null))) {
                throw new SimpleCommandExceptionType(Component.nullToEmpty("You can't leave your own team, use '/allied transfer <player>' or '/allied disband' instead!")).create();
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

        if (containsUuid(requests, playerUuidStr)) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("You have already requested to join this team!")
            ).create();
        }

        requests.add(StringTag.valueOf(playerUuidStr));
        teamData.put("joinRequests", requests);
        try {
            save();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save join request", e);
        }

        // Owners/officers who are offline see pending requests when they log in
        messageManagers(server, teamData, requestMessage(nameOf(server, playerUUID), playerUUID, targetTeamName));
    }

    public void handleRequest(UUID managerUUID, UUID requesterUUID, boolean accept) throws IOException, CommandSyntaxException {
        String ownedTeamName = requireManagedTeam(managerUUID);
        CompoundTag teamData = getTeamData(ownedTeamName);

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

    // Returns true if the invited player was online to see it straight away
    public boolean sendInvite(UUID managerUUID, UUID targetUUID, MinecraftServer server)
            throws CommandSyntaxException, IOException {

        if (isInTeam(targetUUID)) {
            throw new SimpleCommandExceptionType(Component.literal("This player is already in a team!")).create();
        }

        String teamName = requireManagedTeam(managerUUID);
        CompoundTag teamData = getTeamData(teamName);

        int count = getTeamMemberCount(teamName);
        int memberCap = data.getCompoundOrEmpty("settings").getIntOr("maxMembers", 5);

        if (count >= memberCap) {
            throw new SimpleCommandExceptionType(
                    Component.nullToEmpty("Your team is currently full, please kick someone!")
            ).create();
        }

        ListTag invites = teamData.getListOrEmpty("invites");
        String targetStr = targetUUID.toString();

        if (containsUuid(invites, targetStr)) {
            throw new SimpleCommandExceptionType(Component.literal("Player already invited!")).create();
        }

        invites.add(StringTag.valueOf(targetStr));
        teamData.put("invites", invites);
        save();

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(targetUUID);
        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(inviteMessage(teamName));
            return true;
        }
        return false;
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

    public String kickMember(ServerPlayer actor, UUID target, MinecraftServer server) throws CommandSyntaxException, IOException {
        String teamName = requireManagedTeam(actor.getUUID());
        CompoundTag teamData = getTeamData(teamName);

        Role actorRole = getRole(teamData, actor.getUUID());
        Role targetRole = getRole(teamData, target);

        if (targetRole == null) {
            throw new SimpleCommandExceptionType(Component.literal(nameOf(server, target) + " is not in your team!")).create();
        }
        if (targetRole == Role.OWNER) {
            throw new SimpleCommandExceptionType(Component.literal("You can't kick the team owner!")).create();
        }
        if (targetRole == Role.OFFICER && actorRole != Role.OWNER) {
            throw new SimpleCommandExceptionType(Component.literal("Only the team owner can kick officers!")).create();
        }

        removeUuid(teamData.getListOrEmpty("members"), target.toString());
        removeUuid(teamData.getListOrEmpty("officers"), target.toString());
        save();

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(Component.literal("You were kicked from team ")
                    .append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW)));
        }

        return nameOf(server, target);
    }

    public MutableComponent getTeamInfo(MinecraftServer server, String teamName) {
        CompoundTag teamData = getTeamData(teamName);

        if (teamData.isEmpty()) {
            return Component.literal("Team not found!").withStyle(ChatFormatting.RED);
        }

        MutableComponent info = Component.literal("Team Name: ").withStyle(ChatFormatting.GOLD);
        info.append(Component.literal(teamName).withStyle(ChatFormatting.YELLOW)).append(Component.literal("\n"));

        String tag = teamData.getString("teamTag").orElse("No Tag");
        info.append(Component.literal("Team Tag: ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(tag).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n"));

        MutableComponent owner = Component.empty();
        MutableComponent officers = Component.empty();
        MutableComponent members = Component.empty();
        int officerCount = 0;
        int memberCount = 0;

        for (UUID uuid : getTeamPlayers(teamData)) {
            boolean online = server.getPlayerList().getPlayer(uuid) != null;
            Component name = Component.literal(nameOf(server, uuid))
                    .withStyle(online ? ChatFormatting.GREEN : ChatFormatting.GRAY);

            switch (getRole(teamData, uuid)) {
                case OWNER -> owner.append(name);
                case OFFICER -> {
                    if (officerCount++ > 0) officers.append(Component.literal(", "));
                    officers.append(name);
                }
                case MEMBER -> {
                    if (memberCount++ > 0) members.append(Component.literal(", "));
                    members.append(name);
                }
                case null -> {}
            }
        }

        info.append(Component.literal("Owner: ").withStyle(ChatFormatting.GOLD)).append(owner).append(Component.literal("\n"));
        info.append(Component.literal("Officers: ").withStyle(ChatFormatting.GOLD))
                .append(officerCount > 0 ? officers : Component.literal("None").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"));
        info.append(Component.literal("Members: ").withStyle(ChatFormatting.GOLD))
                .append(memberCount > 0 ? members : Component.literal("None").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"));
        info.append(Component.literal("Green = online, gray = offline").withStyle(ChatFormatting.DARK_GRAY));

        return info;
    }

    public static CompoundTag createTeam(String teamTag, UUID ownerUUID) {
        CompoundTag teamData = new CompoundTag();

        teamData.putString("teamTag", teamTag);
        teamData.putString("tagColor", "WHITE");

        teamData.putString("owner", ownerUUID.toString());

        teamData.putString("id", UUID.randomUUID().toString());

        teamData.put("members", new ListTag());
        teamData.put("officers", new ListTag());
        teamData.put("marks", new CompoundTag());
        teamData.putBoolean("storageEnabled", false);

        teamData.put("joinRequests", new ListTag());
        teamData.put("invites", new ListTag());

        CompoundTag settings = new CompoundTag();

        settings.putBoolean("friendlyFire", false);
        settings.putBoolean("highlight", false);
        settings.putBoolean("allowRequests", true);
        settings.putBoolean("chatUseTag", true);
        settings.putBoolean("tabUseTag", true);
        settings.putBoolean("deathCoords", true);

        teamData.put("settings", settings);

        return teamData;
    }
}

