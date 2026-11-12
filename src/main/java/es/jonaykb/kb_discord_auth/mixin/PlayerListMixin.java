package es.jonaykb.kb_discord_auth.mixin;

import java.net.SocketAddress;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import es.jonaykb.kb_discord_auth.server.Limbo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;

    /** Lets players with a "bypass full" Discord role join when the server is full (integrated/LAN servers). */
    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$bypassFull(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        if (AuthManager.canBypassFull(profile)) cir.setReturnValue(true);
    }

    /**
     * Vanilla kicks the player already online when someone joins with the same UUID ("logged in from another
     * location"), before any Discord login. On offline-mode servers anyone can pick any name, so that would let
     * them kick whoever they want. Refuse the new connection instead while the one online has logged in.
     */
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void kbDiscordAuth$keepLoggedInPlayer(SocketAddress address, GameProfile profile,
            CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() != null) return;
        Component reason = AuthManager.duplicateLoginReason(profile);
        if (reason != null) cir.setReturnValue(reason);
    }

    /** Sends the joining player to the waiting room before the client learns where they really are. */
    @Inject(method = "load", at = @At("RETURN"), cancellable = true)
    private void kbDiscordAuth$toLimbo(ServerPlayer player, CallbackInfoReturnable<CompoundTag> cir) {
        cir.setReturnValue(Limbo.onLoad(server, player, cir.getReturnValue()));
    }

    /** A player in the waiting room is not saved: their file keeps their real position and inventory. */
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$skipLimboSave(ServerPlayer player, CallbackInfo ci) {
        if (Limbo.skipSave(player)) ci.cancel();
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void kbDiscordAuth$forgetLimbo(ServerPlayer player, CallbackInfo ci) {
        Limbo.forget(player);
    }
}
