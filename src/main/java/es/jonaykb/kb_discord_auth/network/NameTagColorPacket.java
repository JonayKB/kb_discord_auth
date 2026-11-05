package es.jonaykb.kb_discord_auth.network;

import java.util.UUID;
import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.client.ClientNameTags;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server → client: the role color of a player's name tag (0xRRGGBB), or {@link #NONE} to leave it as it is. */
public record NameTagColorPacket(UUID player, int color) {
    public static final int NONE = -1;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(player);
        buf.writeInt(color);
    }

    public static NameTagColorPacket decode(FriendlyByteBuf buf) {
        return new NameTagColorPacket(buf.readUUID(), buf.readInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientNameTags.set(player, color));
        ctx.get().setPacketHandled(true);
    }
}
