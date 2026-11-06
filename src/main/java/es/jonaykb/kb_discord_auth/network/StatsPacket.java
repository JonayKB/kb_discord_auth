package es.jonaykb.kb_discord_auth.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import es.jonaykb.kb_discord_auth.client.ClientStats;
import es.jonaykb.kb_discord_auth.stats.PlayerStats;
import es.jonaykb.kb_discord_auth.stats.StatKind;
import es.jonaykb.kb_discord_auth.stats.StatsRow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server → client: data for the stats screen. {@code rows} holds the player and the top of each ranking;
 * {@code ranks} is the player's position in each one, in {@link StatKind} order. With {@code open} false
 * it only refreshes a screen that is already open.
 */
public record StatsPacket(boolean open, UUID self, int total, int[] ranks, List<StatsRow> rows) {
    private static final int MAX_ROWS = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeUUID(self);
        buf.writeVarInt(total);
        buf.writeVarIntArray(ranks);
        buf.writeVarInt(rows.size());
        for (StatsRow r : rows) {
            buf.writeUUID(r.uuid());
            buf.writeUtf(r.name(), 64);
            PlayerStats s = r.stats();
            buf.writeVarLong(s.playTimeSeconds());
            buf.writeVarInt(s.deaths());
            buf.writeVarInt(s.mobKills());
            buf.writeVarLong(s.distanceCm());
            buf.writeVarInt(s.advancements());
        }
    }

    public static StatsPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        UUID self = buf.readUUID();
        int total = buf.readVarInt();
        int[] ranks = buf.readVarIntArray(StatKind.values().length);
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<StatsRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID uuid = buf.readUUID();
            String name = buf.readUtf(64);
            rows.add(new StatsRow(uuid, name, new PlayerStats(
                    buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(), buf.readVarInt())));
        }
        return new StatsPacket(open, self, total, ranks, rows);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientStats.onData(this));
        ctx.get().setPacketHandled(true);
    }
}
