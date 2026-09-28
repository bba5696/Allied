package com.bba.allied.storage;

import com.bba.allied.data.datManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

public class StorageContainer extends SimpleContainer {
    teamStorage.Session session;

    public StorageContainer() {
        super(teamStorage.SIZE);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (session != null) session.dirty = true;
    }

    // Checked every tick by vanilla; returning false closes the screen, which saves and releases the lock
    @Override
    public boolean stillValid(Player player) {
        if (session == null || !session.isActive()) return false;
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.isAlive()) return false;

        datManager dm = datManager.get();
        String teamName = dm.getTeam(player.getUUID());
        if (teamName == null) return false;

        CompoundTag team = dm.getTeamData(teamName);
        return team.getBooleanOr("storageEnabled", false)
                && session.teamId.equals(team.getStringOr("id", ""));
    }
}
