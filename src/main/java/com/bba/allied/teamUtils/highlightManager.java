package com.bba.allied.teamUtils;

import com.bba.allied.data.datManager;
import com.bba.allied.mixin.EntityAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;

import java.util.*;

// Shows an outline around invisible teammates, only to their own team.
// The glow is never set on the player itself; it's added to the entity data packets sent to teammates.
public final class highlightManager {

    private static final int FLAG_GLOWING = 6;

    // viewer uuid -> teammates currently shown glowing to that viewer
    private static final Map<UUID, Set<UUID>> glowing = new HashMap<>();

    public static boolean shouldGlow(ServerPlayer viewer, Entity target) {
        if (!(target instanceof ServerPlayer targetPlayer) || targetPlayer == viewer) return false;
        if (!targetPlayer.hasEffect(MobEffects.INVISIBILITY)) return false;

        String viewerTeam = datManager.get().getTeam(viewer.getUUID());
        if (viewerTeam == null || !viewerTeam.equals(datManager.get().getTeam(targetPlayer.getUUID()))) return false;

        return datManager.get().getData()
                .getCompoundOrEmpty("teams")
                .getCompoundOrEmpty(viewerTeam)
                .getCompoundOrEmpty("settings")
                .getBoolean("highlight")
                .orElse(false);
    }

    @SuppressWarnings("unchecked")
    public static Packet<?> modifyPacket(ServerPlayer viewer, Packet<?> packet) {
        if (packet instanceof ClientboundSetEntityDataPacket dataPacket) {
            return modifyDataPacket(viewer, dataPacket);
        }

        // Entity data is bundled with the spawn packet when a player comes into view
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> subPackets = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                Packet<?> modified = modifyPacket(viewer, sub);
                changed |= modified != sub;
                subPackets.add((Packet<? super ClientGamePacketListener>) modified);
            }
            return changed ? new ClientboundBundlePacket(subPackets) : packet;
        }

        return packet;
    }

    private static Packet<?> modifyDataPacket(ServerPlayer viewer, ClientboundSetEntityDataPacket packet) {
        EntityDataAccessor<Byte> flagsAccessor = EntityAccessor.allied$getSharedFlagsId();
        int flagsId = flagsAccessor.id();

        // Cheap checks first, this runs for every entity data packet
        Entity target = viewer.level().getEntity(packet.id());
        if (!(target instanceof ServerPlayer) || !shouldGlow(viewer, target)) return packet;

        List<SynchedEntityData.DataValue<?>> items = new ArrayList<>();
        boolean hasFlags = false;
        for (SynchedEntityData.DataValue<?> item : packet.packedItems()) {
            if (item.id() == flagsId && item.value() instanceof Byte flags) {
                items.add(SynchedEntityData.DataValue.create(flagsAccessor, (byte) (flags | (1 << FLAG_GLOWING))));
                hasFlags = true;
            } else {
                items.add(item);
            }
        }

        if (!hasFlags) {
            byte flags = target.getEntityData().get(flagsAccessor);
            items.add(SynchedEntityData.DataValue.create(flagsAccessor, (byte) (flags | (1 << FLAG_GLOWING))));
        }

        return new ClientboundSetEntityDataPacket(packet.id(), items);
    }

    // Resends a player's flags to teammates whenever their outline should turn on or off
    // (invisibility gained/lost, highlight toggled, team joined/left)
    public static void tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> online = new HashSet<>();

        for (ServerPlayer viewer : players) {
            online.add(viewer.getUUID());

            Set<UUID> now = new HashSet<>();
            for (ServerPlayer target : players) {
                if (shouldGlow(viewer, target)) now.add(target.getUUID());
            }

            Set<UUID> before = glowing.getOrDefault(viewer.getUUID(), Set.of());
            if (!now.equals(before)) {
                for (ServerPlayer target : players) {
                    UUID id = target.getUUID();
                    if (now.contains(id) != before.contains(id)) {
                        sendFlags(viewer, target);
                    }
                }
            }

            if (now.isEmpty()) {
                glowing.remove(viewer.getUUID());
            } else {
                glowing.put(viewer.getUUID(), now);
            }
        }

        glowing.keySet().retainAll(online);
    }

    private static void sendFlags(ServerPlayer viewer, ServerPlayer target) {
        EntityDataAccessor<Byte> flagsAccessor = EntityAccessor.allied$getSharedFlagsId();
        byte flags = target.getEntityData().get(flagsAccessor);
        // Goes through the packet mixin, which adds the glow bit when it should be shown
        viewer.connection.send(new ClientboundSetEntityDataPacket(
                target.getId(),
                List.of(SynchedEntityData.DataValue.create(flagsAccessor, flags))
        ));
    }
}
