package es.jonaykb.kb_discord_auth.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import es.jonaykb.kb_discord_auth.server.Limbo;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public ServerPlayer player;

    /**
     * Packets from other mods (abilities, backpacks...) wait until the player has logged in with Discord, and are
     * handled then. Holding them instead of dropping them keeps mods that sync something on join working.
     */
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$holdUntilLogin(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (player != null && Limbo.holdPacket(player, packet)) ci.cancel();
    }

    /**
     * A player who joins dead can't respawn until they log in: respawning would put them at their bed (often their
     * base) and replace the player object the waiting room protects. Their death screen comes back after the login.
     */
    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$noRespawnBeforeLogin(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN || player == null) return;
        if (!player.getServer().isSameThread()) return; // vanilla moves it to the server thread first
        AuthManager auth = AuthManager.get();
        if (auth != null && auth.isRestricted(player)) ci.cancel();
    }
}
