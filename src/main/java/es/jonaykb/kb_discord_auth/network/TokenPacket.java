package es.jonaykb.kb_discord_auth.network;

import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client → server, answering the Hello. {@code proof} shows the client holds its saved token without sending it
 * ("" if it has none). {@code publicKey} is a throwaway X25519 key used to agree on a new token if one is issued.
 */
public record TokenPacket(String proof, byte[] publicKey) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(proof, AuthCrypto.PROOF_LENGTH);
        buf.writeByteArray(publicKey);
    }

    public static TokenPacket decode(FriendlyByteBuf buf) {
        return new TokenPacket(buf.readUtf(AuthCrypto.PROOF_LENGTH), buf.readByteArray(AuthCrypto.MAX_PUBLIC_KEY_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        AuthManager auth = AuthManager.get();
        if (player != null && auth != null) auth.onToken(player, proof, publicKey);
        ctx.get().setPacketHandled(true);
    }
}
