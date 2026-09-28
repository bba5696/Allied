package com.bba.allied.mixin;

import com.bba.allied.storage.teamStorage;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    // Write any pending team storage changes before vanilla autosaves player data
    @Inject(method = "saveAll", at = @At("HEAD"))
    private void allied$flushTeamStorage(CallbackInfo ci) {
        teamStorage.flushAll();
    }
}
