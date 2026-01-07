package com.bba.allied.teamUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class teamChatManager {
    private static final HashSet<UUID> TEAM_CHAT = new HashSet<>();

    public static void enable(UUID uuid) {
        TEAM_CHAT.add(uuid);
    }

    public static void disable(UUID uuid) {
        TEAM_CHAT.remove(uuid);
    }

    public static boolean isEnabled(UUID uuid) {
        return TEAM_CHAT.contains(uuid);
    }

    public static boolean toggle(UUID uuid) {
        if (isEnabled(uuid)) {
            disable(uuid);
            return false;
        } else {
            enable(uuid);
            return true;
        }
    }
}


