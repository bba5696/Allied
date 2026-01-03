package com.bba.allied.mixin;

import com.bba.allied.data.datManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(
            method = "getPlayerListName", // tablist only
            at = @At("RETURN"),
            cancellable = true
    )
    private void allied$tablistName(CallbackInfoReturnable<Text> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity)(Object)this;

        NbtCompound teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String uuid = player.getUuid().toString();

        for (String teamName : teams.getKeys()) {
            NbtCompound team = teams.getCompoundOrEmpty(teamName);

            boolean isOwner = team.getString("owner").orElse("").equals(uuid);
            boolean isMember = team.getListOrEmpty("members")
                    .stream()
                    .anyMatch(e -> e.asString().orElse("").equals(uuid));

            if (isOwner || isMember) {
                String tag = team.getString("teamTag").orElse(teamName).toUpperCase();
                String colorStr = team.getString("tagColor").orElse("WHITE");

                Formatting tagColor;
                try {
                    tagColor = Formatting.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    tagColor = Formatting.WHITE;
                }

                Text tabName = Text.literal("[")
                        .formatted(Formatting.WHITE)
                        .append(Text.literal(tag).formatted(tagColor))
                        .append(Text.literal("] "))
                        .append(player.getName()).formatted(Formatting.WHITE);

                cir.setReturnValue(tabName);
                return;
            }
        }

        // No team → keep vanilla
        cir.setReturnValue(player.getName());
    }
}
