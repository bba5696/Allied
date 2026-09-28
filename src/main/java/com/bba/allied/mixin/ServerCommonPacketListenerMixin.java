package com.bba.allied.mixin;

import com.bba.allied.teamUtils.highlightManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    // Every packet to a player passes through here, so teammate outlines can be added per viewer
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> allied$addTeammateGlow(Packet<?> packet) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) {
            return highlightManager.modifyPacket(game.player, packet);
        }
        return packet;
    }
}
