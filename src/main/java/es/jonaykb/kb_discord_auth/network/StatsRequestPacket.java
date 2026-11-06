package es.jonaykb.kb_discord_auth.network;

import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.stats.StatsCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Client → server: the "Refresh" button of the stats screen. */
public record StatsRequestPacket() {
    public void encode(FriendlyByteBuf buf) {}

    public static StatsRequestPacket decode(FriendlyByteBuf buf) {
        return new StatsRequestPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) StatsCommand.sendScreen(player, false);
        ctx.get().setPacketHandled(true);
    }
}
