package es.jonaykb.kb_discord_auth.network;

import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Client → server: the saved session token for this server, or "" if there is none. */
public record TokenPacket(String token) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(token, 128);
    }

    public static TokenPacket decode(FriendlyByteBuf buf) {
        return new TokenPacket(buf.readUtf(128));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        AuthManager auth = AuthManager.get();
        if (player != null && auth != null) auth.onToken(player, token);
        ctx.get().setPacketHandled(true);
    }
}
