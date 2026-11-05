package es.jonaykb.kb_discord_auth.stats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.config.AuthConfig;

/**
 * Sends stats to the bot (contract: docs/stats-contract.md).
 * Stats are cumulative, so only the latest snapshot of each player matters: if a request fails, the
 * players stay queued and go with the next one, and a newer snapshot simply replaces the older one.
 */
final class StatsSender {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static final int CONTRACT_VERSION = 1;
    /** Players per request; the contract allows up to 500. */
    private static final int BATCH = 200;

    record Entry(UUID uuid, String name, @Nullable String discordId, boolean online, PlayerStats stats, long takenAt) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final String serverId;
    private final String version;
    private volatile boolean warnedAuth;
    /** The last send failed: the next successful one is logged, so the error above it is not the last word. */
    private volatile boolean failing;

    StatsSender(String serverId, String version) {
        this.serverId = serverId;
        this.version = version;
    }

    void queue(Entry e) {
        pending.merge(e.uuid(), e, (old, neu) -> neu.takenAt() >= old.takenAt() ? neu : old);
    }

    /** Sends everything queued, in the background. Does nothing if a send is still running. */
    void flush() {
        if (pending.isEmpty() || !inFlight.compareAndSet(false, true)) return;
        sendAll().whenComplete((ok, err) -> inFlight.set(false));
    }

    /** Used when the server stops: waits a few seconds so the last stats are not lost. */
    void flushBlocking() {
        if (pending.isEmpty()) return;
        try {
            // A send in progress already holds part of the queue; wait for it before sending the rest.
            long until = System.currentTimeMillis() + 5000;
            while (inFlight.get() && System.currentTimeMillis() < until) Thread.sleep(50);
            sendAll().get(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Could not send the last stats to the bot before stopping: {}", e.toString());
        }
    }

    private CompletableFuture<Void> sendAll() {
        List<Entry> taken = new ArrayList<>();
        for (UUID id : List.copyOf(pending.keySet())) {
            Entry e = pending.remove(id);
            if (e != null) taken.add(e);
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < taken.size(); i += BATCH) {
            List<Entry> batch = taken.subList(i, Math.min(taken.size(), i + BATCH));
            chain = chain.thenCompose(v -> send(batch));
        }
        return chain;
    }

    private CompletableFuture<Void> send(List<Entry> batch) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(AuthConfig.STATS_ENDPOINT.get().trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + AuthConfig.STATS_API_KEY.get().trim())
                    .header("User-Agent", "kb_discord_auth/" + version)
                    .POST(HttpRequest.BodyPublishers.ofString(body(batch), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            LOGGER.error("stats.endpoint is not a valid URL: {}", AuthConfig.STATS_ENDPOINT.get());
            return CompletableFuture.completedFuture(null);
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((res, err) -> {
                    if (err != null) {
                        failing = true;
                        LOGGER.warn("Could not send stats to the bot ({}), will retry: {}", batch.size(), err.toString());
                        requeue(batch);
                    } else if (res.statusCode() / 100 == 2) {
                        if (failing) LOGGER.info("The bot is accepting stats again ({} players sent)", batch.size());
                        failing = false;
                        warnedAuth = false;
                    } else if (res.statusCode() == 400 || res.statusCode() == 413) {
                        failing = true;
                        // The bot rejected the data itself: retrying the same thing would fail forever.
                        LOGGER.error("The bot rejected the stats ({}): {}", res.statusCode(), shorten(res.body()));
                    } else {
                        failing = true;
                        if (res.statusCode() == 401 || res.statusCode() == 403) {
                            if (!warnedAuth) LOGGER.error("The bot refused stats.apiKey ({}). Check it in the config.", res.statusCode());
                            warnedAuth = true;
                        } else {
                            LOGGER.warn("The bot answered {} to the stats, will retry: {}", res.statusCode(), shorten(res.body()));
                        }
                        requeue(batch);
                    }
                    return null;
                });
    }

    /** Puts a failed batch back, unless a newer snapshot of the same player was queued meanwhile. */
    private void requeue(List<Entry> batch) {
        for (Entry e : batch) queue(e);
    }

    private String body(List<Entry> batch) {
        JsonObject root = new JsonObject();
        root.addProperty("version", CONTRACT_VERSION);
        JsonObject server = new JsonObject();
        server.addProperty("id", serverId);
        server.addProperty("name", AuthConfig.SERVER_NAME.get());
        root.add("server", server);
        root.addProperty("sentAt", System.currentTimeMillis());
        JsonArray players = new JsonArray();
        for (Entry e : batch) {
            JsonObject p = new JsonObject();
            p.addProperty("uuid", e.uuid().toString());
            p.addProperty("name", e.name());
            p.addProperty("discordId", e.discordId());
            p.addProperty("online", e.online());
            p.addProperty("takenAt", e.takenAt());
            Map<String, Number> s = new LinkedHashMap<>();
            s.put("playTimeSeconds", e.stats().playTimeSeconds());
            s.put("deaths", e.stats().deaths());
            s.put("mobKills", e.stats().mobKills());
            s.put("distanceCm", e.stats().distanceCm());
            s.put("advancements", e.stats().advancements());
            p.add("stats", GSON.toJsonTree(s));
            players.add(p);
        }
        root.add("players", players);
        return GSON.toJson(root);
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
