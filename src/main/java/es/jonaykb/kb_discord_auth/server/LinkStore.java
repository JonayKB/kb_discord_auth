package es.jonaykb.kb_discord_auth.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

/**
 * Minecraft account ↔ Discord account links, stored in config/kb_discord_auth/links.json.
 * Only touched from the server thread.
 */
public final class LinkStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class LinkRecord {
        public String minecraftName;
        public String discordId;
        public String discordUsername;
        public String discordGlobalName;
        /** Discord avatar hash; null = default avatar. */
        public String discordAvatar;
        public boolean inGuild;
        public List<String> roles = new ArrayList<>();
        /** SHA-256 of the session token stored by the player's client. */
        public String clientTokenHash;
        /** Only used when no bot token is configured. */
        public String accessToken;
        public String refreshToken;
        public long accessTokenExpiresAt;
        public long linkedAt;
        public long rolesSyncedAt;
    }

    private static final class Data {
        String serverId;
        Map<String, LinkRecord> links = new LinkedHashMap<>();
    }

    private final Path file;
    private Data data = new Data();

    public LinkStore(Path file) {
        this.file = file;
    }

    public void load() {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Data loaded = GSON.fromJson(r, Data.class);
                if (loaded != null) data = loaded;
                if (data.links == null) data.links = new LinkedHashMap<>();
            } catch (Exception e) {
                // Never start with an empty store over a broken file: it would unlink everyone on save.
                throw new IllegalStateException("Could not read " + file + ". Fix or delete it.", e);
            }
        }
        if (data.serverId == null) {
            byte[] id = new byte[16];
            new SecureRandom().nextBytes(id);
            data.serverId = HexFormat.of().formatHex(id);
            save();
        }
        LOGGER.info("Loaded {} Discord links", data.links.size());
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }

    /** Random id for this server, so clients can keep one token per server. */
    public String serverId() {
        return data.serverId;
    }

    @Nullable
    public LinkRecord get(UUID uuid) {
        return data.links.get(uuid.toString());
    }

    public void put(UUID uuid, LinkRecord record) {
        data.links.put(uuid.toString(), record);
    }

    @Nullable
    public LinkRecord remove(UUID uuid) {
        return data.links.remove(uuid.toString());
    }

    @Nullable
    public UUID findByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, LinkRecord> e : data.links.entrySet()) {
            String n = e.getValue().minecraftName;
            if (n != null && n.toLowerCase(Locale.ROOT).equals(lower)) return UUID.fromString(e.getKey());
        }
        return null;
    }

    /** Minecraft accounts linked to this Discord account, except {@code except}. */
    public int countByDiscord(String discordId, UUID except) {
        int n = 0;
        for (Map.Entry<String, LinkRecord> e : data.links.entrySet()) {
            if (discordId.equals(e.getValue().discordId) && !e.getKey().equals(except.toString())) n++;
        }
        return n;
    }

    public Collection<String> names() {
        List<String> out = new ArrayList<>();
        for (LinkRecord r : data.links.values()) {
            if (r.minecraftName != null) out.add(r.minecraftName);
        }
        return out;
    }
}
