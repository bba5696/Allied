package com.bba.allied.storage;

import com.bba.allied.data.datManager;
import com.bba.allied.mixin.PlayerListAccessor;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.serialization.DynamicOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * Shared team storage with strictly one viewer at a time.
 *
 * Duplication safety:
 * - The lock lives only on the server (SESSIONS) and is released when the menu is removed,
 *   which vanilla does on screen close, death, disconnect and kick, plus a disconnect hook as a backstop.
 * - Contents are loaded from disk on open and written atomically (temp file + rename) whenever they change,
 *   at the end of that tick, before every autosave and on close.
 * - The viewer's player data is saved together with the storage so the two can't disagree after a crash.
 *   Whichever side lost items is written first, so a crash between the two writes loses items rather than duplicating them.
 */
public final class teamStorage {
    public static final int SIZE = 18;

    private static final Logger LOGGER = LoggerFactory.getLogger("allied");
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("allied").resolve("storage");
    private static final Path AUDIT_LOG = DIR.resolve("audit.log");

    // team id -> the one open session for that team
    private static final Map<String, Session> SESSIONS = new HashMap<>();

    public static final class Session {
        final String teamId;
        final String teamName;
        final ServerPlayer player;
        final StorageContainer container;
        final Map<String, Integer> openedWith;
        int lastSavedCount;
        int lastPlayerHash;
        boolean dirty;
        boolean closed;

        Session(String teamId, String teamName, ServerPlayer player, StorageContainer container) {
            this.teamId = teamId;
            this.teamName = teamName;
            this.player = player;
            this.container = container;
            this.openedWith = countItems(container.getItems());
            this.lastSavedCount = total(openedWith);
            this.lastPlayerHash = playerHash(player);
        }

        public boolean isActive() {
            return !closed && SESSIONS.get(teamId) == this;
        }
    }

    public static void open(ServerPlayer player) throws CommandSyntaxException {
        datManager dm = datManager.get();
        String teamName = dm.requireTeam(player.getUUID());
        CompoundTag team = dm.getTeamData(teamName);

        if (!team.getBooleanOr("storageEnabled", false)) {
            throw error("Team storage is disabled for your team. A server admin can enable it.");
        }

        String teamId = team.getStringOr("id", "");
        if (teamId.isEmpty()) throw error("Your team has no storage id, please contact a server admin.");

        Session existing = SESSIONS.get(teamId);
        if (existing != null) {
            if (!existing.player.getUUID().equals(player.getUUID())) {
                // Refused, not queued
                throw error(existing.player.getGameProfile().name() + " is using the team storage right now. Try again when they close it.");
            }
            // Same player reopening: close the old view first so it's saved and released
            player.closeContainer();
            close(existing);
        }

        MinecraftServer server = player.level().getServer();
        StorageContainer container = new StorageContainer();
        try {
            load(server, teamId, container);
        } catch (IOException e) {
            // Never open (and later overwrite) a file we couldn't read
            LOGGER.error("Failed to read team storage for {} ({})", teamName, teamId, e);
            throw error("The team storage could not be loaded. Please contact a server admin.");
        }

        Session session = new Session(teamId, teamName, player, container);
        container.session = session;
        SESSIONS.put(teamId, session);

        OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, p) -> new StorageMenu(syncId, inventory, container, session),
                Component.literal(teamName + " Storage")
        ));

        if (opened.isEmpty()) {
            SESSIONS.remove(teamId, session);
            session.closed = true;
            throw error("Could not open the team storage.");
        }

        audit("OPEN", session, "");
    }

    // Called by StorageMenu.removed (screen close, death, disconnect) and the disconnect hook
    public static void close(Session session) {
        if (session.closed) return;
        session.closed = true;

        flush(session);
        SESSIONS.remove(session.teamId, session);

        Map<String, Integer> now = countItems(session.container.getItems());
        audit("CLOSE", session, describeChanges(session.openedWith, now));
    }

    public static void onDisconnect(ServerPlayer player) {
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            if (session.player.getUUID().equals(player.getUUID())) {
                close(session);
            }
        }
    }

    public static void tick() {
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            // Moving an item from the cursor into the player's inventory doesn't touch the storage,
            // so the player's own inventory is watched too; otherwise a crash could lose that item
            if (session.dirty || playerHash(session.player) != session.lastPlayerHash) {
                flush(session);
            }
        }
    }

    // Before vanilla autosaves player data, so a saved player is never ahead of the saved storage
    public static void flushAll() {
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            flush(session);
        }
    }

    public static void closeAll() {
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            session.player.closeContainer();
            close(session);
        }
    }

    public static boolean isOpen(String teamId) {
        return SESSIONS.containsKey(teamId);
    }

    // True when the storage file holds at least one item
    public static boolean hasItems(MinecraftServer server, String teamId) throws IOException {
        StorageContainer container = new StorageContainer();
        load(server, teamId, container);
        return !container.isEmpty();
    }

    private static void flush(Session session) {
        session.dirty = false;
        int count = total(countItems(session.container.getItems()));

        try {
            if (count < session.lastSavedCount) {
                // Items were taken out: save the storage first
                writeStorage(session);
                savePlayer(session.player);
            } else {
                // Items were put in: save the player first
                savePlayer(session.player);
                writeStorage(session);
            }
            session.lastSavedCount = count;
            session.lastPlayerHash = playerHash(session.player);
        } catch (IOException e) {
            LOGGER.error("Failed to save team storage for {} ({})", session.teamName, session.teamId, e);
            session.dirty = true;
        }
    }

    // Cheap fingerprint of the player's inventory and cursor item
    private static int playerHash(ServerPlayer player) {
        int hash = 1;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            hash = 31 * hash + (stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount());
        }
        ItemStack carried = player.containerMenu.getCarried();
        hash = 31 * hash + (carried.isEmpty() ? 0 : ItemStack.hashItemAndComponents(carried) * 31 + carried.getCount());
        return hash;
    }

    private static void savePlayer(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        ((PlayerListAccessor) server.getPlayerList()).allied$getPlayerIo().save(player);
    }

    private static Path fileFor(String teamId) {
        return DIR.resolve(teamId + ".dat");
    }

    private static void writeStorage(Session session) throws IOException {
        MinecraftServer server = session.player.level().getServer();
        DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        ListTag items = new ListTag();
        NonNullList<ItemStack> stacks = session.container.getItems();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) continue;

            Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow(IOException::new);
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) slot);
            entry.put("item", encoded);
            items.add(entry);
        }

        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        root.putString("team", session.teamName);
        root.put("items", items);

        Files.createDirectories(DIR);
        Path path = fileFor(session.teamId);
        Path tmp = DIR.resolve(session.teamId + ".dat.tmp");
        NbtIo.write(root, tmp);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void load(MinecraftServer server, String teamId, StorageContainer container) throws IOException {
        Path path = fileFor(teamId);
        if (!Files.exists(path)) return;

        CompoundTag root = NbtIo.read(path);
        if (root == null) throw new IOException("Empty storage file " + path);

        DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag items = root.getListOrEmpty("items");
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompoundOrEmpty(i);
            int slot = entry.getByteOr("Slot", (byte) -1);
            if (slot < 0 || slot >= SIZE) continue;

            Tag itemTag = entry.get("item");
            if (itemTag == null) continue;

            ItemStack stack = ItemStack.CODEC.parse(ops, itemTag).getOrThrow(IOException::new);
            container.getItems().set(slot, stack);
        }
    }

    private static Map<String, Integer> countItems(List<ItemStack> stacks) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static int total(Map<String, Integer> counts) {
        int total = 0;
        for (int count : counts.values()) total += count;
        return total;
    }

    private static String describeChanges(Map<String, Integer> before, Map<String, Integer> after) {
        List<String> added = new ArrayList<>();
        List<String> taken = new ArrayList<>();

        Set<String> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            int diff = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (diff > 0) added.add(diff + "x " + id);
            if (diff < 0) taken.add(-diff + "x " + id);
        }

        if (added.isEmpty() && taken.isEmpty()) return "no changes";
        StringBuilder sb = new StringBuilder();
        if (!added.isEmpty()) sb.append("added ").append(String.join(", ", added));
        if (!taken.isEmpty()) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append("taken ").append(String.join(", ", taken));
        }
        return sb.toString();
    }

    private static void audit(String action, Session session, String details) {
        String line = Instant.now() + " " + action
                + " team=\"" + session.teamName + "\" (" + session.teamId + ")"
                + " player=" + session.player.getGameProfile().name() + " (" + session.player.getUUID() + ")"
                + (details.isEmpty() ? "" : " " + details);

        LOGGER.info("[Team Storage] {}", line);
        try {
            Files.createDirectories(DIR);
            Files.writeString(AUDIT_LOG, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("Failed to write team storage audit log", e);
        }
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
