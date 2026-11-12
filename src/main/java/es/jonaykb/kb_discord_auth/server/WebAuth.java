package es.jonaykb.kb_discord_auth.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;

import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.discord.DiscordApi;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.DiscordUser;
import es.jonaykb.kb_discord_auth.server.LinkStore.LinkRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Discord login for websites of this server (e.g. the BlueMap web). It reuses the game's redirectUri, so nothing
 * changes on Discord. The browser keeps a bearer token; other mods answer {@code /web/api/<name>} for the
 * Minecraft accounts linked to that Discord account (see {@link #registerApi}).
 *
 * <pre>
 * GET  /web/login?return=URL ─► Discord ─► callback ─► URL?kbauth=CODE   (one-time code, 2 min)
 * POST /web/session  (body CODE)      ─► {token, user}
 * GET  /web/me                        ─► {user, accounts}
 * GET  /web/api/NAME?...              ─► whatever the mod returns
 * POST /web/logout
 * </pre>
 * Only the origins in {@code web.allowedOrigins} can be returned to and get CORS headers.
 * Runs on the HTTP threads; the link store is read on the server thread.
 */
public final class WebAuth {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long STATE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long CODE_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private static final int MAX_BODY = 4096;

    /**
     * Data handlers added by other mods (IMC "web_api"). Arguments: the Minecraft accounts (UUID → name) of the
     * logged-in Discord user that are allowed on the server, newest link first, and the query parameters.
     * Returns a JSON body, or null for 404. Called on an HTTP thread.
     */
    private static final Map<String, BiFunction<Map<UUID, String>, Map<String, String>, String>> APIS = new ConcurrentHashMap<>();

    public static void registerApi(String name, BiFunction<Map<UUID, String>, Map<String, String>, String> handler) {
        if (!name.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Bad web api name: " + name);
        APIS.put(name, handler);
        LOGGER.info("Web API /web/api/{} registered", name);
    }

    private record PendingLogin(String returnUrl, long createdAt) {}

    private record Code(DiscordUser user, long createdAt) {}

    private static final class WebSession {
        String discordId;
        String username;
        String globalName;
        String avatar;
        long createdAt;
        long expiresAt;
    }

    private final MinecraftServer server;
    private final AuthManager auth;
    private final DiscordApi api = new DiscordApi();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingLogin> states = new ConcurrentHashMap<>();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Path file = FMLPaths.CONFIGDIR.get().resolve("kb_discord_auth").resolve("web_sessions.json");
    /** sha256(token) → session. Guarded by this. */
    private final Map<String, WebSession> sessions = new LinkedHashMap<>();

    WebAuth(MinecraftServer server, AuthManager auth) {
        this.server = server;
        this.auth = auth;
        load();
    }

    // ───────────────────────────── routing ─────────────────────────────

    boolean ownsState(@Nullable String state) {
        return state != null && states.containsKey(state);
    }

    /** Everything under /web/. */
    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String origin = allowedOrigin(ex.getRequestHeaders().getFirst("Origin"));
        if (origin != null) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST");
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
        ex.getResponseHeaders().add("Vary", "Origin");
        ex.getResponseHeaders().set("Cache-Control", "no-store");

        if ("OPTIONS".equals(method)) {
            ex.sendResponseHeaders(origin != null ? 204 : 403, -1);
            return;
        }
        if (path.equals("/web/login") && "GET".equals(method)) {
            login(ex);
        } else if (path.equals("/web/session") && "POST".equals(method)) {
            exchangeCode(ex);
        } else if (path.equals("/web/me") && "GET".equals(method)) {
            me(ex);
        } else if (path.equals("/web/logout") && "POST".equals(method)) {
            logout(ex);
        } else if (path.startsWith("/web/api/") && "GET".equals(method)) {
            api(ex, path.substring("/web/api/".length()));
        } else {
            json(ex, 404, error("not_found"));
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String returnUrl = CallbackServer.parseQuery(ex.getRequestURI().getRawQuery()).get("return");
        if (returnUrl == null || allowedOrigin(originOf(returnUrl)) == null) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        prune();
        String state = randomToken();
        states.put(state, new PendingLogin(returnUrl, System.currentTimeMillis()));
        redirect(ex, DiscordApi.authorizeUrl(state, "identify"));
    }

    /** Discord sent the browser back with a web state. */
    void handleCallback(HttpExchange ex, Map<String, String> q) throws IOException {
        PendingLogin pending = states.remove(q.get("state"));
        if (pending == null || System.currentTimeMillis() - pending.createdAt() > STATE_TTL_MS) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        if (q.containsKey("error") || q.get("code") == null) {
            redirect(ex, withParam(pending.returnUrl(), "kbauth_error", "cancelled"));
            return;
        }
        try {
            DiscordUser user = api.me(api.exchangeCode(q.get("code")).accessToken());
            String code = randomToken();
            codes.put(code, new Code(user, System.currentTimeMillis()));
            LOGGER.info("Web login of Discord @{} ({})", user.username(), user.id());
            redirect(ex, withParam(pending.returnUrl(), "kbauth", code));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("Web Discord login failed: {}", e.toString());
            redirect(ex, withParam(pending.returnUrl(), "kbauth_error", "discord"));
        }
    }

    private void exchangeCode(HttpExchange ex) throws IOException {
        if (allowedOrigin(ex.getRequestHeaders().getFirst("Origin")) == null) {
            json(ex, 403, error("origin"));
            return;
        }
        String body = readBody(ex).trim();
        Code code = codes.remove(body);
        if (code == null || System.currentTimeMillis() - code.createdAt() > CODE_TTL_MS) {
            json(ex, 401, error("expired"));
            return;
        }
        String token = randomToken();
        WebSession s = new WebSession();
        s.discordId = code.user().id();
        s.username = code.user().username();
        s.globalName = code.user().globalName();
        s.avatar = code.user().avatar();
        s.createdAt = System.currentTimeMillis();
        s.expiresAt = s.createdAt + TimeUnit.DAYS.toMillis(AuthConfig.WEB_SESSION_DAYS.get());
        synchronized (this) {
            sessions.put(sha256(token), s);
            save();
        }
        JsonObject out = new JsonObject();
        out.addProperty("token", token);
        out.addProperty("expiresAt", s.expiresAt);
        out.add("user", userJson(s));
        json(ex, 200, out.toString());
    }

    private void me(HttpExchange ex) throws IOException {
        WebSession s = session(ex);
        if (s == null) {
            json(ex, 401, error("login"));
            return;
        }
        List<Account> accounts = accounts(s.discordId);
        if (accounts == null) {
            json(ex, 503, error("busy"));
            return;
        }
        JsonObject out = new JsonObject();
        out.add("user", userJson(s));
        JsonArray list = new JsonArray();
        for (Account a : accounts) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", a.uuid().toString());
            o.addProperty("name", a.name());
            list.add(o);
        }
        out.add("accounts", list);
        json(ex, 200, out.toString());
    }

    private void logout(HttpExchange ex) throws IOException {
        String token = bearer(ex);
        if (token != null) {
            synchronized (this) {
                if (sessions.remove(sha256(token)) != null) save();
            }
        }
        ex.sendResponseHeaders(204, -1);
    }

    private void api(HttpExchange ex, String name) throws IOException {
        BiFunction<Map<UUID, String>, Map<String, String>, String> handler = APIS.get(name);
        if (handler == null) {
            json(ex, 404, error("not_found"));
            return;
        }
        WebSession s = session(ex);
        if (s == null) {
            json(ex, 401, error("login"));
            return;
        }
        List<Account> accounts = accounts(s.discordId);
        if (accounts == null) {
            json(ex, 503, error("busy"));
            return;
        }
        String body;
        try {
            Map<UUID, String> byId = new LinkedHashMap<>();
            for (Account a : accounts) byId.put(a.uuid(), a.name());
            body = handler.apply(java.util.Collections.unmodifiableMap(byId),
                    CallbackServer.parseQuery(ex.getRequestURI().getRawQuery()));
        } catch (Exception e) {
            LOGGER.warn("Web API {} failed", name, e);
            json(ex, 500, error("internal"));
            return;
        }
        if (body == null) json(ex, 404, error("not_found"));
        else json(ex, 200, body);
    }

    // ───────────────────────────── sessions ─────────────────────────────

    private record Account(UUID uuid, String name) {}

    /** Linked accounts that are currently allowed on the server, newest link first. Null if the server is busy. */
    @Nullable
    private List<Account> accounts(String discordId) {
        CompletableFuture<List<Account>> f = CompletableFuture.supplyAsync(() -> {
            List<Map.Entry<UUID, LinkRecord>> found = new ArrayList<>();
            auth.store().forEach((uuid, r) -> {
                if (discordId.equals(r.discordId) && AuthManager.denyReason(r) == null) found.add(Map.entry(uuid, r));
            });
            found.sort((a, b) -> Long.compare(b.getValue().linkedAt, a.getValue().linkedAt));
            return found.stream().map(e -> new Account(e.getKey(), e.getValue().minecraftName)).toList();
        }, server);
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    @Nullable
    private synchronized WebSession session(HttpExchange ex) {
        String token = bearer(ex);
        if (token == null) return null;
        String key = sha256(token);
        WebSession s = sessions.get(key);
        if (s == null) return null;
        if (s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(key);
            save();
            return null;
        }
        return s;
    }

    @Nullable
    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        states.values().removeIf(p -> now - p.createdAt() > STATE_TTL_MS);
        codes.values().removeIf(c -> now - c.createdAt() > CODE_TTL_MS);
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, WebSession> read = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, WebSession>>() {}.getType());
            if (read != null) sessions.putAll(read);
        } catch (Exception e) {
            // Losing web sessions only means logging in again on the web.
            LOGGER.warn("Could not read {}: {}", file, e.toString());
        }
    }

    private synchronized void save() {
        long now = System.currentTimeMillis();
        for (Iterator<WebSession> it = sessions.values().iterator(); it.hasNext(); ) {
            if (it.next().expiresAt < now) it.remove();
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(sessions, w);
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

    // ───────────────────────────── helpers ─────────────────────────────

    /** The origin itself if it is in web.allowedOrigins, else null. */
    @Nullable
    static String allowedOrigin(@Nullable String origin) {
        if (origin == null) return null;
        for (String allowed : AuthConfig.WEB_ALLOWED_ORIGINS.get()) {
            if (allowed.equalsIgnoreCase(origin)) return allowed;
        }
        return null;
    }

    @Nullable
    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() == null || u.getHost() == null || u.getRawUserInfo() != null) return null;
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            return scheme + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Adds a query parameter before the #fragment (BlueMap keeps the camera position there). */
    static String withParam(String url, String key, String value) {
        int hash = url.indexOf('#');
        String base = hash < 0 ? url : url.substring(0, hash);
        String fragment = hash < 0 ? "" : url.substring(hash);
        String param = key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        return base + (base.contains("?") ? "&" : "?") + param + fragment;
    }

    private static JsonObject userJson(WebSession s) {
        JsonObject u = new JsonObject();
        u.addProperty("id", s.discordId);
        u.addProperty("username", s.username);
        u.addProperty("globalName", s.globalName);
        u.addProperty("avatarUrl", avatarUrl(s.discordId, s.avatar));
        return u;
    }

    private static String avatarUrl(String id, @Nullable String avatar) {
        if (avatar != null && avatar.matches("[a-z0-9_]{1,64}")) {
            return "https://cdn.discordapp.com/avatars/" + id + "/" + avatar + ".png?size=64";
        }
        long index = (Long.parseLong(id) >> 22) % 6;
        return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
    }

    private static String error(String code) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        return o.toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(302, -1);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
