package com.bba.allied.mixin;

import com.bba.allied.data.datManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void allied$tablistName(CallbackInfoReturnable<Component> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;

        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String uuid = player.getUUID().toString();

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            boolean isOwner = team.getString("owner").orElse("").equals(uuid);
            boolean isMember = team.getListOrEmpty("members")
                    .stream()
                    .anyMatch(e -> e.asString().orElse("").equals(uuid));

            if (isOwner || isMember) {
                boolean tabUseTag = team.getCompoundOrEmpty("settings").getBoolean("tabUseTag").orElse(true);
                String tag;
                if (tabUseTag) {
                    tag = team.getString("teamTag").orElse(teamName).toUpperCase();
                } else {
                    tag = teamName.toUpperCase();
                }
                String colorStr = team.getString("tagColor").orElse("WHITE");

                ChatFormatting tagColor;
                try {
                    tagColor = ChatFormatting.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    tagColor = ChatFormatting.WHITE;
                }

                Component tabName = Component.literal("[")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(tag).withStyle(tagColor))
                        .append(Component.literal("] "))
                        .append(player.getName()).withStyle(ChatFormatting.WHITE);

                cir.setReturnValue(tabName);
                return;
            }
        }
    }
}
