package es.jonaykb.kb_discord_auth.network;

import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.client.ClientAuth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server → client: show the Discord login screen with this URL. {@code message} is an optional error. */
public record OpenAuthPacket(String url, String message, int secondsLeft) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(url, 2048);
        buf.writeUtf(message, 1024);
        buf.writeVarInt(secondsLeft);
    }

    public static OpenAuthPacket decode(FriendlyByteBuf buf) {
        return new OpenAuthPacket(buf.readUtf(2048), buf.readUtf(1024), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientAuth.onOpenAuth(this));
        ctx.get().setPacketHandled(true);
    }
}
