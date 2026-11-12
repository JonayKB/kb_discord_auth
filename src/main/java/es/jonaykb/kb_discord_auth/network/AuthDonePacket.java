package es.jonaykb.kb_discord_auth.network;

import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.client.ClientAuth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server → client: logged in. {@code serverPublicKey} is the server's half of the key agreement the new session
 * token comes from (see {@link AuthCrypto}), or empty to keep the current token. The token itself is never sent.
 */
public record AuthDonePacket(byte[] serverPublicKey) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByteArray(serverPublicKey);
    }

    public static AuthDonePacket decode(FriendlyByteBuf buf) {
        return new AuthDonePacket(buf.readByteArray(AuthCrypto.MAX_PUBLIC_KEY_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientAuth.onDone(this));
        ctx.get().setPacketHandled(true);
    }
}
