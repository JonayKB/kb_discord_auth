package es.jonaykb.kb_discord_auth.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.network.StatsPacket;
import es.jonaykb.kb_discord_auth.server.AuthManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Stats of every player for /stats: live ones for whoever is online, and the saved files for the rest.
 * The files are read on another thread and refreshed at most once a minute. Everything here runs on the server thread.
 */
public final class StatsLeaderboard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_AGE_MS = 60_000;
    public static final int TOP = 10;

    private final MinecraftServer server;
    private Map<java.util.UUID, StatsRow> saved = Map.of();
    private long savedAt;
    private boolean loading;
    private final List<Consumer<List<StatsRow>>> waiting = new ArrayList<>();

    StatsLeaderboard(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Runs {@code action} on the server thread with all players. Right away if the saved files have been read
     * before (refreshing them in the background when they are over a minute old), or once the first read ends.
     */
    public void withRows(Consumer<List<StatsRow>> action) {
        if (savedAt != 0) {
            action.accept(rows());
            if (System.currentTimeMillis() - savedAt >= MAX_AGE_MS) reload();
            return;
        }
        waiting.add(action);
        reload();
    }

    /** Reads the saved files in the background. Called once at startup so the first /stats is instant. */
    void reload() {
        if (loading) return;
        loading = true;
        List<SavedStats.Known> known = SavedStats.known(server);
        SavedStats.Source src = SavedStats.source(server);
        CompletableFuture.supplyAsync(() -> {
            Map<java.util.UUID, StatsRow> read = new HashMap<>();
            for (SavedStats.Known k : known) {
                try {
                    read.put(k.uuid(), new StatsRow(k.uuid(), k.name(), SavedStats.read(server, src, k.uuid())));
                } catch (Exception e) {
                    LOGGER.debug("Could not read the stats of {}: {}", k.name(), e.toString());
                }
            }
            return read;
        }).whenComplete((read, error) -> server.execute(() -> {
            loading = false;
            if (read != null) {
                saved = read;
            } else {
                LOGGER.warn("Could not read the saved stats: {}", String.valueOf(error));
            }
            savedAt = System.currentTimeMillis();
            if (waiting.isEmpty()) return;
            List<Consumer<List<StatsRow>>> run = new ArrayList<>(waiting);
            waiting.clear();
            List<StatsRow> rows = rows();
            for (Consumer<List<StatsRow>> a : run) a.accept(rows);
        }));
    }

    /** Saved stats with the live ones of online players on top. */
    private List<StatsRow> rows() {
        Map<java.util.UUID, StatsRow> all = new HashMap<>(saved);
        AuthManager auth = AuthManager.get();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            // Someone who hasn't logged in may be using another player's name: keep that player's saved stats.
            if (auth != null && auth.isRestricted(p)) continue;
            if (AuthManager.isLoadTestBot(p)) continue;
            all.put(p.getUUID(), new StatsRow(p.getUUID(), p.getGameProfile().getName(), PlayerStats.of(p)));
        }
        return new ArrayList<>(all.values());
    }

    /** Names for command suggestions, without reading any file. */
    public Collection<String> names() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (StatsRow r : saved.values()) names.add(r.name());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!AuthManager.isLoadTestBot(p)) names.add(p.getGameProfile().getName());
        }
        return names;
    }

    /** Position of {@code row} in each ranking (1 = first), in {@link StatKind} order. */
    public static int[] ranks(StatsRow row, List<StatsRow> rows) {
        int[] ranks = new int[StatKind.values().length];
        for (StatKind kind : StatKind.values()) {
            List<StatsRow> sorted = new ArrayList<>(rows);
            sorted.sort(kind.order(StatsRow::stats, StatsRow::name));
            ranks[kind.ordinal()] = sorted.indexOf(row) + 1;
        }
        return ranks;
    }

    /** What the stats screen needs: the player, their positions and the top of every ranking. */
    public static StatsPacket packetFor(ServerPlayer player, List<StatsRow> rows, boolean open) {
        StatsRow self = rows.stream().filter(r -> r.uuid().equals(player.getUUID())).findFirst().orElse(null);
        if (self == null) {
            self = new StatsRow(player.getUUID(), player.getGameProfile().getName(), PlayerStats.of(player));
            rows = new ArrayList<>(rows);
            rows.add(self);
        }
        Set<StatsRow> picked = new LinkedHashSet<>();
        picked.add(self);
        for (StatKind kind : StatKind.values()) {
            List<StatsRow> sorted = new ArrayList<>(rows);
            sorted.sort(kind.order(StatsRow::stats, StatsRow::name));
            picked.addAll(sorted.subList(0, Math.min(TOP, sorted.size())));
        }
        return new StatsPacket(open, self.uuid(), rows.size(), ranks(self, rows), new ArrayList<>(picked));
    }
}
