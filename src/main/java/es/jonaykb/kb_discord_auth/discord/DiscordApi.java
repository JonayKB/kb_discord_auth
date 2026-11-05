package es.jonaykb.kb_discord_auth.discord;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import es.jonaykb.kb_discord_auth.config.AuthConfig;

/** Minimal blocking Discord REST client. Never call it from the server thread. */
public final class DiscordApi {
    /** Only for testing against a fake Discord: -Dkb_discord_auth.apiBase=http://127.0.0.1:18999/api/v10 */
    private static final String API = System.getProperty("kb_discord_auth.apiBase", "https://discord.com/api/v10");
    private static final String USER_AGENT = "DiscordBot (https://github.com/JonayKB/kb_discord_auth, 1.3)";

    public record TokenResponse(String accessToken, String refreshToken, long expiresAtMillis) {}

    /** {@code avatar} is the avatar hash, null when the user has the default avatar. */
    public record DiscordUser(String id, String username, String globalName, @Nullable String avatar) {}

    /**
     * Roles of a guild member, or {@code inGuild=false} when the user is not in the guild.
     * {@code avatarKnown} tells whether the response had the user, so {@code avatar} (null = default avatar) is valid.
     */
    public record Membership(boolean inGuild, List<String> roles, @Nullable String avatar, boolean avatarKnown) {
        public static final Membership NOT_IN_GUILD = new Membership(false, List.of(), null, false);
    }

    public static final class DiscordException extends IOException {
        public final int status;

        public DiscordException(int status, String body) {
            super("Discord API returned " + status + ": " + body);
            this.status = status;
        }

        /** The refresh token / code is no longer valid: the player has to log in again. */
        public boolean isInvalidGrant() {
            return status == 400 || status == 401;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static String scopes() {
        return AuthConfig.useBot() ? "identify" : "identify guilds.members.read";
    }

    public static String authorizeUrl(String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", AuthConfig.CLIENT_ID.get());
        q.put("response_type", "code");
        q.put("redirect_uri", AuthConfig.REDIRECT_URI.get());
        q.put("scope", scopes());
        q.put("state", state);
        q.put("prompt", "none");
        return "https://discord.com/oauth2/authorize?" + form(q);
    }

    public TokenResponse exchangeCode(String code) throws IOException, InterruptedException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", AuthConfig.REDIRECT_URI.get());
        return tokenRequest(body);
    }

    public TokenResponse refresh(String refreshToken) throws IOException, InterruptedException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        return tokenRequest(body);
    }

    private TokenResponse tokenRequest(Map<String, String> body) throws IOException, InterruptedException {
        body.put("client_id", AuthConfig.CLIENT_ID.get());
        body.put("client_secret", AuthConfig.CLIENT_SECRET.get());
        HttpRequest req = base(API + "/oauth2/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build();
        JsonObject json = send(req).getAsJsonObject();
        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
        return new TokenResponse(
                json.get("access_token").getAsString(),
                json.has("refresh_token") ? json.get("refresh_token").getAsString() : null,
                System.currentTimeMillis() + expiresIn * 1000L);
    }

    public DiscordUser me(String accessToken) throws IOException, InterruptedException {
        JsonObject json = send(base(API + "/users/@me")
                .header("Authorization", "Bearer " + accessToken)
                .GET().build()).getAsJsonObject();
        return new DiscordUser(
                json.get("id").getAsString(),
                json.get("username").getAsString(),
                json.has("global_name") && !json.get("global_name").isJsonNull()
                        ? json.get("global_name").getAsString() : json.get("username").getAsString(),
                stringOrNull(json, "avatar"));
    }

    /** Uses the bot token: GET /guilds/{guild}/members/{user}. */
    public Membership memberWithBot(String userId) throws IOException, InterruptedException {
        return member(base(API + "/guilds/" + AuthConfig.GUILD_ID.get() + "/members/" + userId)
                .header("Authorization", "Bot " + AuthConfig.BOT_TOKEN.get())
                .GET().build());
    }

    /** Uses the player's token with the guilds.members.read scope. */
    public Membership memberWithUserToken(String accessToken) throws IOException, InterruptedException {
        return member(base(API + "/users/@me/guilds/" + AuthConfig.GUILD_ID.get() + "/member")
                .header("Authorization", "Bearer " + accessToken)
                .GET().build());
    }

    /** A guild role. {@code color} is 0xRRGGBB, 0 when the role has no color. */
    public record GuildRole(String id, String name, int color, int position) {}

    /** Uses the bot token: GET /guilds/{guild}/roles. */
    public List<GuildRole> guildRoles() throws IOException, InterruptedException {
        List<GuildRole> out = new ArrayList<>();
        send(base(API + "/guilds/" + AuthConfig.GUILD_ID.get() + "/roles")
                .header("Authorization", "Bot " + AuthConfig.BOT_TOKEN.get())
                .GET().build()).getAsJsonArray().forEach(e -> {
                    JsonObject r = e.getAsJsonObject();
                    out.add(new GuildRole(r.get("id").getAsString(), r.get("name").getAsString(),
                            r.has("color") ? r.get("color").getAsInt() : 0, r.get("position").getAsInt()));
                });
        return out;
    }

    private Membership member(HttpRequest req) throws IOException, InterruptedException {
        try {
            JsonObject json = send(req).getAsJsonObject();
            List<String> roles = new ArrayList<>();
            json.getAsJsonArray("roles").forEach(e -> roles.add(e.getAsString()));
            JsonObject user = json.has("user") && json.get("user").isJsonObject() ? json.getAsJsonObject("user") : null;
            return new Membership(true, List.copyOf(roles), user != null ? stringOrNull(user, "avatar") : null, user != null);
        } catch (DiscordException e) {
            // 404 Unknown Member / Unknown Guild: the user is not in the guild.
            if (e.status == 404) return Membership.NOT_IN_GUILD;
            throw e;
        }
    }

    private static HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json");
    }

    private JsonElement send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 429) {
            // Rate limited: wait what Discord asks (capped) and retry once.
            long waitMs = 1000;
            Optional<String> retry = res.headers().firstValue("Retry-After");
            if (retry.isPresent()) {
                try {
                    waitMs = (long) (Double.parseDouble(retry.get()) * 1000);
                } catch (NumberFormatException ignored) {
                }
            }
            Thread.sleep(Math.min(waitMs, 5000));
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (res.statusCode() / 100 != 2) {
            throw new DiscordException(res.statusCode(), truncate(res.body()));
        }
        return JsonParser.parseString(res.body());
    }

    @Nullable
    private static String stringOrNull(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
