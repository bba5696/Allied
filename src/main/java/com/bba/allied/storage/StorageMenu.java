package com.bba.allied.storage;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

// A vanilla 2-row chest screen, so no client mod is needed
public class StorageMenu extends ChestMenu {
    private final teamStorage.Session session;

    public StorageMenu(int syncId, Inventory inventory, StorageContainer container, teamStorage.Session session) {
        super(MenuType.GENERIC_9x2, syncId, inventory, container, 2);
        this.session = session;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        teamStorage.close(session);
    }
}
