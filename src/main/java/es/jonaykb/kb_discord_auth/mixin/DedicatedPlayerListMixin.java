package es.jonaykb.kb_discord_auth.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import net.minecraft.server.dedicated.DedicatedPlayerList;

/**
 * Same as {@link PlayerListMixin}, for dedicated servers: DedicatedPlayerList overrides the method
 * (ops with bypassesPlayerLimit), so it needs its own hook. Vanilla ops keep working.
 */
@Mixin(DedicatedPlayerList.class)
public abstract class DedicatedPlayerListMixin {
    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$bypassFull(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        if (AuthManager.canBypassFull(profile)) cir.setReturnValue(true);
    }
}
