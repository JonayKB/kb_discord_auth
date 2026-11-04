package es.jonaykb.kb_discord_auth.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import net.minecraft.server.players.PlayerList;

/** Lets players with a "bypass full" Discord role join when the server is full (integrated/LAN servers). */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$bypassFull(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        if (AuthManager.canBypassFull(profile)) cir.setReturnValue(true);
    }
}
