package es.jonaykb.kb_discord_auth.stats;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.KbDiscordAuth;
import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.config.RoleDisplay;
import es.jonaykb.kb_discord_auth.server.AuthManager;
import es.jonaykb.kb_discord_auth.server.LinkStore.LinkRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/** Tab list header/footer with each player's own stats, and periodic sending of stats to the bot. */
public final class StatsManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable private static StatsManager instance;

    private final MinecraftServer server;
    private final StatsLeaderboard leaderboard;
    @Nullable private StatsSender sender;
    private long ticks;

    private StatsManager(MinecraftServer server) {
        this.server = server;
        this.leaderboard = new StatsLeaderboard(server);
    }

    /** Called after {@link AuthManager#start}, which loads the links (and the server id). */
    public static void start(MinecraftServer server) {
        StatsManager m = new StatsManager(server);
        instance = m;
        m.leaderboard.reload();
        if (!AuthConfig.STATS_ENABLED.get()) return;
        if (!AuthConfig.statsConfigured()) {
            LOGGER.error("stats.enabled is true but stats.endpoint or stats.apiKey is empty: no stats will be sent.");
            return;
        }
        AuthManager auth = AuthManager.get();
        String serverId = auth != null ? auth.store().serverId() : "";
        String version = ModList.get().getModContainerById(KbDiscordAuth.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        m.sender = new StatsSender(serverId, version);
        LOGGER.info("Sending player stats to {} every {} min", AuthConfig.STATS_ENDPOINT.get(), AuthConfig.STATS_INTERVAL_MINUTES.get());
        if (AuthConfig.STATS_BACKFILL.get()) m.backfill();
    }

    public static void stop() {
        StatsManager m = instance;
        instance = null;
        if (m != null && m.sender != null) m.sender.flushBlocking();
    }

    @Nullable
    public static StatsManager get() {
        return instance;
    }

    /** Rankings for /stats. Available even when nothing is sent to the bot. */
    public StatsLeaderboard leaderboard() {
        return leaderboard;
    }

    // ───────── events ─────────

    public void tick() {
        ticks++;
        if (AuthConfig.TAB_ENABLED.get() && ticks % (AuthConfig.TAB_UPDATE_SECONDS.get() * 20L) == 0) updateTab();
        if (sender != null && ticks % (AuthConfig.STATS_INTERVAL_MINUTES.get() * 1200L) == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!restricted(p)) sender.queue(entry(p, true));
            }
            sender.flush();
        }
    }

    public void onLogin(ServerPlayer player) {
        if (AuthConfig.TAB_ENABLED.get()) sendTab(player, server.getPlayerList().getPlayerCount());
    }

    public void onLogout(ServerPlayer player) {
        if (sender == null || restricted(player)) return;
        sender.queue(entry(player, false));
        sender.flush();
    }

    /** Players who haven't logged in with Discord yet may be using someone else's name: don't report them. */
    private static boolean restricted(ServerPlayer p) {
        AuthManager auth = AuthManager.get();
        return auth != null && auth.isRestricted(p);
    }

    // ───────── tab list ─────────

    private void updateTab() {
        int online = server.getPlayerList().getPlayerCount();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sendTab(p, online);
    }

    private void sendTab(ServerPlayer player, int online) {
        PlayerStats s = PlayerStats.of(player);
        Component header = lines(AuthConfig.TAB_HEADER.get(), player, s, online);
        Component footer = lines(AuthConfig.TAB_FOOTER.get(), player, s, online);
        player.connection.send(new ClientboundTabListPacket(header, footer));
    }

    /** Each line starts with a reset so colors and strikethrough never leak into the next one. */
    private Component lines(List<? extends String> template, ServerPlayer player, PlayerStats s, int online) {
        StringBuilder out = new StringBuilder();
        for (String line : template) {
            if (out.length() > 0) out.append('\n');
            out.append("\u00a7r").append(format(line, player, s, online));
        }
        return Component.literal(out.toString());
    }

    private String format(String template, ServerPlayer player, PlayerStats s, int online) {
        String text = template
                .replace("{server}", AuthConfig.SERVER_NAME.get())
                .replace("{online}", Integer.toString(online))
                .replace("{max}", Integer.toString(server.getPlayerList().getMaxPlayers()))
                .replace("{player}", player.getGameProfile().getName())
                .replace("{playtime}", s.playTime())
                .replace("{deaths}", Integer.toString(s.deaths()))
                .replace("{mobKills}", Integer.toString(s.mobKills()))
                .replace("{distance}", s.distance())
                .replace("{advancements}", Integer.toString(s.advancements()));
        return RoleDisplay.legacy(text);
    }

    // ───────── stats for the bot ─────────

    private StatsSender.Entry entry(ServerPlayer p, boolean online) {
        return new StatsSender.Entry(p.getUUID(), p.getGameProfile().getName(), discordId(p.getUUID()), online,
                PlayerStats.of(p), System.currentTimeMillis());
    }

    @Nullable
    private static String discordId(UUID uuid) {
        AuthManager auth = AuthManager.get();
        LinkRecord r = auth == null ? null : auth.store().get(uuid);
        return r == null ? null : r.discordId;
    }

    /**
     * Sends the saved stats of every player who has ever joined, so the leaderboard is complete from the
     * first day. Names and links are looked up here on the server thread; the files are read on another one.
     */
    private void backfill() {
        List<SavedStats.Known> players = SavedStats.known(server);
        if (players.isEmpty()) return;
        SavedStats.Source src = SavedStats.source(server);
        StatsSender s = sender;
        CompletableFuture.runAsync(() -> {
            long now = System.currentTimeMillis();
            for (SavedStats.Known w : players) {
                try {
                    s.queue(new StatsSender.Entry(w.uuid(), w.name(), w.discordId(), false, SavedStats.read(server, src, w.uuid()), now));
                } catch (Exception e) {
                    LOGGER.warn("Could not read the stats of {}: {}", w.name(), e.toString());
                }
            }
            LOGGER.info("Sending saved stats of {} players to the bot", players.size());
            s.flush();
        });
    }
}
