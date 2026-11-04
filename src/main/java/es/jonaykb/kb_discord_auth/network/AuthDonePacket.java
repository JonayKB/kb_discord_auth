package es.jonaykb.kb_discord_auth.network;

import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.client.ClientAuth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server → client: logged in. {@code token} is a new session token to save, or "" to keep the current one. */
public record AuthDonePacket(String token) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(token, 128);
    }

    public static AuthDonePacket decode(FriendlyByteBuf buf) {
        return new AuthDonePacket(buf.readUtf(128));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientAuth.onDone(this));
        ctx.get().setPacketHandled(true);
    }
}
