package es.jonaykb.kb_discord_auth.network;

import java.util.UUID;
import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.client.ClientAuth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server → client on join: "log in, and send me your saved token for this server if you have one". */
public record HelloPacket(String serverId, UUID playerId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(serverId, 64);
        buf.writeUUID(playerId);
    }

    public static HelloPacket decode(FriendlyByteBuf buf) {
        return new HelloPacket(buf.readUtf(64), buf.readUUID());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientAuth.onHello(this));
        ctx.get().setPacketHandled(true);
    }
}
