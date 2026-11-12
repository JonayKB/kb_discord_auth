package es.jonaykb.kb_discord_auth.client;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Session tokens the servers gave us, one per address + server + Minecraft account, in
 * config/kb_discord_auth/client_tokens.json. Sending it on join skips the Discord login.
 */
final class ClientTokenStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private ClientTokenStore() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("kb_discord_auth").resolve("client_tokens.json");
    }

    private static Map<String, String> read() {
        Path f = file();
        if (!Files.exists(f)) return new HashMap<>();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Map<String, String> m = GSON.fromJson(r, TYPE);
            return m == null ? new HashMap<>() : new HashMap<>(m);
        } catch (Exception e) {
            LOGGER.warn("Could not read {}", f, e);
            return new HashMap<>();
        }
    }

    @Nullable
    static String get(String key) {
        return read().get(key);
    }

    static void put(String key, String token) {
        Map<String, String> m = read();
        m.put(key, token);
        write(m);
    }

    /** Removes and returns the token saved under {@code key}, or null. */
    @Nullable
    static String take(String key) {
        Map<String, String> m = read();
        String token = m.remove(key);
        if (token != null) write(m);
        return token;
    }

    private static void write(Map<String, String> m) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(m, TYPE, w);
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Could not save {}", f, e);
        }
    }
}
