package es.jonaykb.kb_discord_auth.server;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.config.RoleDisplay;
import es.jonaykb.kb_discord_auth.discord.DiscordApi;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.DiscordException;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.DiscordUser;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.GuildRole;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.Membership;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.TokenResponse;
import es.jonaykb.kb_discord_auth.network.AuthCrypto;
import es.jonaykb.kb_discord_auth.network.AuthDonePacket;
import es.jonaykb.kb_discord_auth.network.HelloPacket;
import es.jonaykb.kb_discord_auth.network.NameTagColorPacket;
import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.network.OpenAuthPacket;
import es.jonaykb.kb_discord_auth.server.LinkStore.LinkRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Server-side login flow. Everything here runs on the server thread except the Discord calls,
 * which run on {@link #worker} or on the callback HTTP threads.
 *
 * <pre>
 * join ─► Hello(nonce) ─► client proves it holds its saved token (HMAC, the token isn't sent)
 *          ├─ valid proof  ─► refresh roles ─► allowed? ─► AUTHORIZED
 *          └─ no / invalid ─► OpenAuth(url) ─► Discord ─► /callback ─► link + new token (key agreement) ─► allowed? ─► AUTHORIZED
 * </pre>
 * Until AUTHORIZED the player is frozen and can't chat, run commands or interact (see {@link ServerEvents}).
 */
public final class AuthManager {
    private static final String LOAD_TEST_MARKER = "kb_load_test";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile AuthManager instance;

    private enum State { WAITING_TOKEN, CHECKING, WAITING_OAUTH, AUTHORIZED }

    private static final class Session {
        final long joinedAt = System.currentTimeMillis();
        final ResourceKey<Level> dimension;
        final double x, y, z;
        State state = State.WAITING_TOKEN;
        @Nullable String oauthState;
        /** Sent in the Hello; the client's proof must be made with it. */
        final byte[] nonce;
        /** The client's throwaway key, for agreeing on a new token. Null until it answers, or if it sent a bad one. */
        @Nullable PublicKey clientKey;

        Session(ServerPlayer p, byte[] nonce) {
            this.nonce = nonce;
            dimension = p.level().dimension();
            x = p.getX();
            y = p.getY();
            z = p.getZ();
        }
    }

    /** Result of reading a member's roles off the server thread. */
    private record Sync(boolean ok, boolean needsLogin, @Nullable Membership membership, @Nullable TokenResponse tokens) {
        static final Sync FAILED = new Sync(false, false, null, null);
        static final Sync NEEDS_LOGIN = new Sync(false, true, null, null);
    }

    /** What the browser shows after the Discord redirect. */
    public record CallbackResult(boolean success, String title, String message) {}

    private final MinecraftServer server;
    private final LinkStore store;
    private final DiscordApi api = new DiscordApi();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, UUID> oauthStates = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private final SecureRandom random = new SecureRandom();
    @Nullable private CallbackServer callback;
    @Nullable private WebAuth web;
    /** Discord roles of the guild by id. Replaced as a whole, so it is safe to read from any thread. */
    private volatile Map<String, GuildRole> guildRoles = Map.of();
    private long ticks;

    private AuthManager(MinecraftServer server) {
        this.server = server;
        this.store = new LinkStore(FMLPaths.CONFIGDIR.get().resolve("kb_discord_auth").resolve("links.json"));
        AtomicInteger n = new AtomicInteger();
        this.worker = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "kb_discord_auth-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Nullable
    public static AuthManager get() {
        return instance;
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    public static void start(MinecraftServer server) {
        AuthManager m = new AuthManager(server);
        m.store.load();
        instance = m;
        if (!m.active()) {
            LOGGER.info("Discord login is disabled on this server");
            return;
        }
        if (!AuthConfig.isConfigured()) {
            LOGGER.error("Discord login is enabled but not configured: fill clientId, clientSecret, redirectUri and guildId in "
                    + "config/kb_discord_auth-common.toml. Players will be kicked until then.");
            return;
        }
        if (AuthConfig.WEB_ENABLED.get()) m.web = new WebAuth(server, m);
        try {
            CallbackServer cb = new CallbackServer(m);
            cb.start();
            m.callback = cb;
        } catch (Exception e) {
            LOGGER.error("Could not start the Discord callback server on port {}. Players will be kicked until it works.",
                    AuthConfig.HTTP_PORT.get(), e);
        }
        if (!AuthConfig.useBot()) {
            LOGGER.warn("No bot token configured: roles are read with each player's own Discord token (guilds.members.read).");
            if (AuthConfig.AUTOMATIC_ROLES.get()) {
                LOGGER.warn("automaticRoles needs botToken: only the roles listed in display.roles will be shown.");
            }
        }
        m.refreshGuildRoles();
    }

    public static void stop() {
        AuthManager m = instance;
        instance = null;
        if (m == null) return;
        if (m.callback != null) m.callback.stop();
        m.worker.shutdownNow();
        m.store.save();
    }

    /** Web login, or null when web.enabled is false. */
    @Nullable
    WebAuth web() {
        return web;
    }

    public boolean active() {
        return AuthConfig.ENABLED.get() && (server.isDedicatedServer() || AuthConfig.ENABLE_IN_SINGLEPLAYER.get());
    }

    // ───────────────────────────── join / leave ─────────────────────────────

    public void onLogin(ServerPlayer player) {
        if (!active() || isLoadTestBot(player)) return;
        byte[] nonce = new byte[AuthCrypto.NONCE_BYTES];
        random.nextBytes(nonce);
        Session s = new Session(player, nonce);
        sessions.put(player.getUUID(), s);
        if (!AuthConfig.isConfigured() || callback == null) {
            kick(player, AuthConfig.MSG_KICK_MISCONFIGURED.get());
            return;
        }
        Network.sendTo(player, new HelloPacket(store.serverId(), player.getUUID(), nonce));
    }

    public void onLogout(ServerPlayer player) {
        Session s = sessions.remove(player.getUUID());
        if (s != null && s.oauthState != null) oauthStates.remove(s.oauthState);
        if (s != null && s.state == State.AUTHORIZED) {
            Network.sendToAll(new NameTagColorPacket(player.getUUID(), NameTagColorPacket.NONE));
        }
    }

    /**
     * The client answered the Hello: a proof that it holds its saved token ("" if it has none) and its throwaway
     * public key. See {@link AuthCrypto}: the token itself never travels.
     */
    public void onToken(ServerPlayer player, String proof, byte[] publicKey) {
        UUID uuid = player.getUUID();
        Session s = sessions.get(uuid);
        if (s == null || s.state != State.WAITING_TOKEN) return;
        s.clientKey = AuthCrypto.readPublicKey(publicKey);

        LinkRecord r = store.get(uuid);
        if (r == null || r.clientTokenHash == null || proof.isEmpty() || !AuthCrypto.constantTimeEquals(proof,
                AuthCrypto.proofFromHash(r.clientTokenHash, store.serverId(), uuid, s.nonce))) {
            startOAuth(player, s, "");
            return;
        }

        s.state = State.CHECKING;
        r.minecraftName = player.getGameProfile().getName();
        syncAsync(r).thenAcceptAsync(sync -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null || sessions.get(uuid) != s) return;
            if (sync.needsLogin()) {
                startOAuth(p, s, "");
                return;
            }
            apply(r, sync);
            store.save();
            finish(p, s, r, null);
        }, server);
    }

    private void startOAuth(ServerPlayer player, Session s, String message) {
        if (s.oauthState != null) oauthStates.remove(s.oauthState);
        String state = randomToken();
        s.oauthState = state;
        s.state = State.WAITING_OAUTH;
        oauthStates.put(state, player.getUUID());
        Network.sendTo(player, new OpenAuthPacket(DiscordApi.authorizeUrl(state), message, secondsLeft(s)));
    }

    /** Called on an HTTP thread when Discord redirects the browser back. Blocks until done. */
    public CallbackResult handleCallback(@Nullable String code, @Nullable String state) {
        UUID uuid = state == null ? null : oauthStates.get(state);
        if (uuid == null || code == null || !oauthStates.remove(state, uuid)) {
            return new CallbackResult(false, "Enlace caducado",
                    "Este enlace ya no es válido. Vuelve a Minecraft y pulsa otra vez «Iniciar sesión con Discord».");
        }

        TokenResponse tokens;
        DiscordUser user;
        Membership membership;
        try {
            tokens = api.exchangeCode(code);
            user = api.me(tokens.accessToken());
            membership = AuthConfig.useBot() ? api.memberWithBot(user.id()) : api.memberWithUserToken(tokens.accessToken());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("Discord login failed", e);
            server.execute(() -> retryOAuth(uuid, state, "No se pudo contactar con Discord. Inténtalo de nuevo."));
            return new CallbackResult(false, "Error con Discord",
                    "No se pudo completar el inicio de sesión. Vuelve a Minecraft e inténtalo de nuevo.");
        }

        CompletableFuture<CallbackResult> done =
                CompletableFuture.supplyAsync(() -> completeLogin(uuid, state, tokens, user, membership), server);
        try {
            return done.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("Could not finish the Discord login", e);
            return new CallbackResult(false, "Error", "El servidor no ha respondido a tiempo. Vuelve a Minecraft e inténtalo de nuevo.");
        }
    }

    private void retryOAuth(UUID uuid, String state, String message) {
        Session s = sessions.get(uuid);
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (s != null && p != null && state.equals(s.oauthState)) startOAuth(p, s, message);
    }

    private CallbackResult completeLogin(UUID uuid, String state, TokenResponse tokens, DiscordUser user, Membership membership) {
        Session s = sessions.get(uuid);
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (s == null || p == null || !state.equals(s.oauthState)) {
            return new CallbackResult(false, "Ya no estás en el servidor", "Vuelve a entrar en el servidor e inténtalo de nuevo.");
        }
        s.oauthState = null;

        LinkRecord r = store.get(uuid);
        if (r != null && !user.id().equals(r.discordId)) {
            startOAuth(p, s, AuthConfig.MSG_LINKED_TO_OTHER.get());
            return new CallbackResult(false, "Cuenta vinculada a otro Discord", AuthConfig.MSG_LINKED_TO_OTHER.get());
        }
        if (r == null && store.countByDiscord(user.id(), uuid) >= AuthConfig.MAX_ACCOUNTS_PER_DISCORD.get()) {
            startOAuth(p, s, AuthConfig.MSG_TOO_MANY_ACCOUNTS.get());
            return new CallbackResult(false, "Demasiadas cuentas", AuthConfig.MSG_TOO_MANY_ACCOUNTS.get());
        }

        if (r == null) {
            r = new LinkRecord();
            r.linkedAt = System.currentTimeMillis();
            LOGGER.info("Linked {} ({}) to Discord @{} ({})", p.getGameProfile().getName(), uuid, user.username(), user.id());
        }
        r.minecraftName = p.getGameProfile().getName();
        r.discordId = user.id();
        r.discordUsername = user.username();
        r.discordGlobalName = user.globalName();
        r.discordAvatar = user.avatar();
        if (AuthConfig.useBot()) {
            r.accessToken = null;
            r.refreshToken = null;
            r.accessTokenExpiresAt = 0;
        } else {
            r.accessToken = tokens.accessToken();
            r.refreshToken = tokens.refreshToken();
            r.accessTokenExpiresAt = tokens.expiresAtMillis();
        }
        // The new token comes from a key agreement with the client's throwaway key, so it is never sent.
        KeyPair serverKeys = AuthCrypto.newKeyPair();
        String clientToken = s.clientKey == null ? null
                : AuthCrypto.deriveToken(serverKeys.getPrivate(), s.clientKey, store.serverId(), uuid);
        if (clientToken == null) {
            kick(p, AuthConfig.MSG_KICK_BAD_CLIENT.get());
            return new CallbackResult(false, "Error", AuthConfig.MSG_KICK_BAD_CLIENT.get());
        }
        apply(r, new Sync(true, false, membership, null));
        r.clientTokenHash = AuthCrypto.sha256Hex(clientToken);
        store.put(uuid, r);
        store.save();

        String deny = finish(p, s, r, serverKeys.getPublic().getEncoded());
        if (deny != null) return new CallbackResult(false, "No puedes entrar", deny);
        return new CallbackResult(true, "¡Listo, @" + user.username() + "!", "Ya puedes cerrar esta pestaña y volver a Minecraft.");
    }

    /** Lets the player in, or kicks them. Returns the kick reason, or null if they got in. */
    @Nullable
    private String finish(ServerPlayer p, Session s, LinkRecord r, @Nullable byte[] newTokenKey) {
        String deny = denyReason(r);
        if (deny != null) {
            // Keep the new token anyway: once they get the role they can join without logging in again.
            if (newTokenKey != null) Network.sendTo(p, new AuthDonePacket(newTokenKey));
            kick(p, deny);
            return deny;
        }
        s.state = State.AUTHORIZED;
        Network.sendTo(p, new AuthDonePacket(newTokenKey == null ? new byte[0] : newTokenKey));
        refreshName(p);
        sendNameTagsTo(p);
        p.sendSystemMessage(Component.literal(RoleDisplay.legacy(placeholders(AuthConfig.MSG_LOGGED_IN.get(), r))));
        return null;
    }

    @Nullable
    static String denyReason(LinkRecord r) {
        if (AuthConfig.REQUIRE_GUILD_MEMBER.get() && !r.inGuild) return AuthConfig.MSG_KICK_NOT_IN_GUILD.get();
        List<? extends String> required = AuthConfig.REQUIRED_ROLES.get();
        if (!required.isEmpty() && !hasAny(r.roles, required)) {
            return r.inGuild ? AuthConfig.MSG_KICK_MISSING_ROLE.get() : AuthConfig.MSG_KICK_NOT_IN_GUILD.get();
        }
        return null;
    }

    // ───────────────────────────── roles ─────────────────────────────

    /** Reads the member's roles on the worker thread. Never touches the record there: only copies of its fields. */
    private CompletableFuture<Sync> syncAsync(LinkRecord r) {
        String discordId = r.discordId;
        String access = r.accessToken;
        String refresh = r.refreshToken;
        long expiresAt = r.accessTokenExpiresAt;
        return CompletableFuture.supplyAsync(() -> fetch(discordId, access, refresh, expiresAt), worker);
    }

    private Sync fetch(String discordId, @Nullable String access, @Nullable String refresh, long expiresAt) {
        try {
            if (AuthConfig.useBot()) return new Sync(true, false, api.memberWithBot(discordId), null);
            if (refresh == null) return Sync.NEEDS_LOGIN;
            TokenResponse tokens = null;
            if (access == null || System.currentTimeMillis() > expiresAt - 60_000) {
                tokens = api.refresh(refresh);
                access = tokens.accessToken();
            }
            return new Sync(true, false, api.memberWithUserToken(access), tokens);
        } catch (DiscordException e) {
            if (!AuthConfig.useBot() && e.isInvalidGrant()) return Sync.NEEDS_LOGIN;
            LOGGER.warn("Could not read Discord roles of {}, using the cached ones: {}", discordId, e.getMessage());
            return Sync.FAILED;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("Could not read Discord roles of {}, using the cached ones: {}", discordId, e.toString());
            return Sync.FAILED;
        }
    }

    private static void apply(LinkRecord r, Sync sync) {
        if (sync.needsLogin()) {
            r.accessToken = null;
            r.refreshToken = null;
            return;
        }
        if (!sync.ok() || sync.membership() == null) return;
        r.inGuild = sync.membership().inGuild();
        r.roles = new ArrayList<>(sync.membership().roles());
        if (sync.membership().avatarKnown()) r.discordAvatar = sync.membership().avatar();
        r.rolesSyncedAt = System.currentTimeMillis();
        TokenResponse t = sync.tokens();
        if (t != null) {
            r.accessToken = t.accessToken();
            if (t.refreshToken() != null) r.refreshToken = t.refreshToken();
            r.accessTokenExpiresAt = t.expiresAtMillis();
        }
    }

    /** Re-reads the guild roles and the roles of every logged-in player. Returns how many player syncs were started. */
    public int resyncOnline() {
        refreshGuildRoles();
        int started = 0;
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            if (e.getValue().state != State.AUTHORIZED) continue;
            UUID uuid = e.getKey();
            LinkRecord r = store.get(uuid);
            if (r == null) continue;
            started++;
            syncAsync(r).thenAcceptAsync(sync -> {
                if (store.get(uuid) != r) return; // unlinked meanwhile
                apply(r, sync);
                store.save();
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null) return;
                String deny = denyReason(r);
                if (deny != null && AuthConfig.KICK_ON_ROLE_LOSS.get() && sync.ok()) {
                    kick(p, deny);
                    return;
                }
                refreshName(p);
            }, server);
        }
        return started;
    }

    // ───────────────────────────── per tick ─────────────────────────────

    public void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        long timeoutMs = AuthConfig.LOGIN_TIMEOUT_SECONDS.get() * 1000L;
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            Session s = e.getValue();
            if (s.state == State.AUTHORIZED) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            if (now - s.joinedAt > timeoutMs) {
                kick(p, AuthConfig.MSG_KICK_TIMEOUT.get());
                continue;
            }
            // Freeze: keep them where they joined (they can still look around).
            if (p.level().dimension() == s.dimension && p.distanceToSqr(s.x, s.y, s.z) > 0.01) {
                p.connection.teleport(s.x, s.y, s.z, p.getYRot(), p.getXRot());
            }
            p.fallDistance = 0;
            if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        }

        int minutes = AuthConfig.ROLE_SYNC_MINUTES.get();
        if (minutes > 0 && ticks % (minutes * 1200L) == 0) resyncOnline();
    }

    // ───────────────────────────── queries ─────────────────────────────

    /** True while the player still has to log in with Discord. */
    public boolean isRestricted(Player player) {
        if (!(player instanceof ServerPlayer)) return false;
        Session s = sessions.get(player.getUUID());
        return s != null && s.state != State.AUTHORIZED;
    }

    /**
     * Bots from KB Load Test (marked with a profile property that a real client can't send): they don't
     * log in with Discord and must not show up in stats or rankings.
     */
    public static boolean isLoadTestBot(Player player) {
        return player.getGameProfile().getProperties().containsKey(LOAD_TEST_MARKER);
    }

    /** Used by the PlayerList mixin when the server is full. Only trusts accounts that finished a Discord login. */
    public static boolean canBypassFull(GameProfile profile) {
        AuthManager m = instance;
        if (m == null || !m.active() || profile.getId() == null) return false;
        List<? extends String> bypass = AuthConfig.BYPASS_FULL_ROLES.get();
        if (bypass.isEmpty()) return false;
        LinkRecord r = m.store.get(profile.getId());
        return r != null && r.clientTokenHash != null && hasAny(r.roles, bypass);
    }

    /**
     * Used by the PlayerList mixin while someone is joining. Returns why the join is refused when a player with the
     * same UUID is online and has already logged in with Discord, or null to let vanilla handle it. If the one online
     * hasn't logged in yet, the new connection still replaces it: otherwise an impostor could hold the name.
     */
    @Nullable
    public static Component duplicateLoginReason(GameProfile profile) {
        AuthManager m = instance;
        if (m == null || !m.active() || profile.getId() == null) return null;
        Session s = m.sessions.get(profile.getId());
        if (s == null || s.state != State.AUTHORIZED) return null;
        if (m.server.getPlayerList().getPlayer(profile.getId()) == null) return null;
        LOGGER.info("Refused a second connection as {} ({}): that player is already online",
                profile.getName(), profile.getId());
        return Component.literal(RoleDisplay.legacy(AuthConfig.MSG_KICK_ALREADY_ONLINE.get()));
    }

    /** Name with role prefix/color and a hover showing the Discord @, or null to leave it alone. */
    @Nullable
    public Component decorateName(ServerPlayer player, Component name, boolean forTab) {
        Session s = sessions.get(player.getUUID());
        if (s == null || s.state != State.AUTHORIZED) return null;
        LinkRecord r = store.get(player.getUUID());
        if (r == null) return null;
        return NameDecorator.decorate(r, guildRoles, name, forTab);
    }

    /** Re-reads the guild's roles (names, colors, order) and updates every shown name. Needs the bot. */
    public void refreshGuildRoles() {
        if (!AuthConfig.useBot() || !AuthConfig.AUTOMATIC_ROLES.get() || !AuthConfig.isConfigured()) return;
        CompletableFuture.supplyAsync(() -> {
            try {
                return api.guildRoles();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOGGER.warn("Could not read the Discord server roles, keeping the previous ones: {}", e.toString());
                return null;
            }
        }, worker).thenAcceptAsync(list -> {
            if (list == null) return;
            Map<String, GuildRole> roles = new HashMap<>();
            for (GuildRole role : list) roles.put(role.id(), role);
            guildRoles = Map.copyOf(roles);
            refreshAllNames();
        }, server);
    }

    private void refreshAllNames() {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().state != State.AUTHORIZED) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            refreshName(p);
        }
    }

    /** Re-applies the role look of a logged-in player: chat name, tab list and, for everyone, the name tag color. */
    private void refreshName(ServerPlayer p) {
        p.refreshDisplayName();
        p.refreshTabListName();
        Network.sendToAll(new NameTagColorPacket(p.getUUID(), nameTagColor(p.getUUID())));
    }

    /** Sends a player who just logged in the name tag colors of everyone already online. */
    private void sendNameTagsTo(ServerPlayer to) {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().state != State.AUTHORIZED || e.getKey().equals(to.getUUID())) continue;
            int color = nameTagColor(e.getKey());
            if (color != NameTagColorPacket.NONE) Network.sendTo(to, new NameTagColorPacket(e.getKey(), color));
        }
    }

    private int nameTagColor(UUID uuid) {
        LinkRecord r = store.get(uuid);
        if (!AuthConfig.SHOW_IN_NAME_TAG.get() || r == null) return NameTagColorPacket.NONE;
        TextColor color = NameDecorator.nameTagColor(r, guildRoles);
        return color == null ? NameTagColorPacket.NONE : color.getValue();
    }

    // ───────────────────────────── admin ─────────────────────────────

    public LinkStore store() {
        return store;
    }

    @Nullable
    public LinkRecord unlink(UUID uuid) {
        LinkRecord r = store.remove(uuid);
        if (r == null) return null;
        store.save();
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p != null && sessions.containsKey(uuid)) kick(p, AuthConfig.MSG_KICK_UNLINKED.get());
        LOGGER.info("Unlinked {} ({}) from Discord @{}", r.minecraftName, uuid, r.discordUsername);
        return r;
    }

    public Collection<UUID> sessionPlayers() {
        return List.copyOf(sessions.keySet());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private int secondsLeft(Session s) {
        long left = AuthConfig.LOGIN_TIMEOUT_SECONDS.get() - (System.currentTimeMillis() - s.joinedAt) / 1000;
        return (int) Math.max(0, left);
    }

    private static void kick(ServerPlayer p, String message) {
        p.connection.disconnect(Component.literal(RoleDisplay.legacy(message)));
    }

    static String placeholders(String text, LinkRecord r) {
        return text.replace("{username}", nullToEmpty(r.discordUsername))
                .replace("{globalName}", nullToEmpty(r.discordGlobalName))
                .replace("{id}", nullToEmpty(r.discordId));
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static boolean hasAny(Collection<String> roles, List<? extends String> wanted) {
        for (String w : wanted) {
            if (roles.contains(w)) return true;
        }
        return false;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
