package es.jonaykb.kb_discord_auth.stats;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.server.AuthManager;
import es.jonaykb.kb_discord_auth.server.LinkStore.LinkRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.LevelResource;

/** Stats saved in the world folder, for players who are not online. */
final class SavedStats {
    private static final Logger LOGGER = LogUtils.getLogger();

    record Known(UUID uuid, String name, @Nullable String discordId) {}

    /** Where and what to read, taken on the server thread so {@link #read} can run on another one. */
    record Source(Path statsDir, Path advDir, Set<String> countedAdvancements) {}

    private SavedStats() {}

    /** Everyone with a stats file whose name is known. Server thread only. */
    static List<Known> known(MinecraftServer server) {
        Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        List<Known> players = new ArrayList<>();
        GameProfileCache cache = server.getProfileCache();
        AuthManager auth = AuthManager.get();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path f : files) {
                String base = f.getFileName().toString().replace(".json", "");
                UUID uuid;
                try {
                    uuid = UUID.fromString(base);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                LinkRecord link = auth == null ? null : auth.store().get(uuid);
                String name = cache == null ? null : cache.get(uuid).map(GameProfile::getName).orElse(null);
                if (name == null && link != null) name = link.minecraftName;
                if (name == null) continue; // No way to show them on a leaderboard.
                players.add(new Known(uuid, name, link == null ? null : link.discordId));
            }
        } catch (IOException e) {
            if (Files.exists(statsDir)) LOGGER.warn("Could not list {}: {}", statsDir, e.toString());
        }
        return players;
    }

    /** Server thread only. */
    static Source source(MinecraftServer server) {
        return new Source(server.getWorldPath(LevelResource.PLAYER_STATS_DIR),
                server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR),
                PlayerStats.countedAdvancementIds(server));
    }

    /** Reads one player's files. Safe on any thread; throws if the stats file can't be read. */
    static PlayerStats read(MinecraftServer server, Source src, UUID uuid) {
        ServerStatsCounter counter = new ServerStatsCounter(server, src.statsDir().resolve(uuid + ".json").toFile());
        return PlayerStats.of(counter, countDone(src.advDir().resolve(uuid + ".json"), src.countedAdvancements()));
    }

    private static int countDone(Path file, Set<String> counted) {
        if (!Files.exists(file)) return 0;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            int n = 0;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (!counted.contains(e.getKey()) || !e.getValue().isJsonObject()) continue;
                JsonElement done = e.getValue().getAsJsonObject().get("done");
                if (done != null && done.getAsBoolean()) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
