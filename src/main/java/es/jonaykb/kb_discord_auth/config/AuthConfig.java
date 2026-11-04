package es.jonaykb.kb_discord_auth.config;

import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * COMMON config (config/kb_discord_auth-common.toml). It is never synced to clients,
 * which matters because it holds the Discord client secret and bot token.
 */
public final class AuthConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue ENABLE_IN_SINGLEPLAYER;
    public static final ForgeConfigSpec.IntValue LOGIN_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_ACCOUNTS_PER_DISCORD;

    public static final ForgeConfigSpec.ConfigValue<String> CLIENT_ID;
    public static final ForgeConfigSpec.ConfigValue<String> CLIENT_SECRET;
    public static final ForgeConfigSpec.ConfigValue<String> REDIRECT_URI;
    public static final ForgeConfigSpec.ConfigValue<String> GUILD_ID;
    public static final ForgeConfigSpec.ConfigValue<String> BOT_TOKEN;

    public static final ForgeConfigSpec.ConfigValue<String> HTTP_BIND_ADDRESS;
    public static final ForgeConfigSpec.IntValue HTTP_PORT;

    public static final ForgeConfigSpec.BooleanValue REQUIRE_GUILD_MEMBER;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REQUIRED_ROLES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BYPASS_FULL_ROLES;
    public static final ForgeConfigSpec.IntValue ROLE_SYNC_MINUTES;
    public static final ForgeConfigSpec.BooleanValue KICK_ON_ROLE_LOSS;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ROLE_DISPLAY;
    public static final ForgeConfigSpec.ConfigValue<String> HOVER_FORMAT;
    public static final ForgeConfigSpec.BooleanValue SHOW_IN_TAB;

    public static final ForgeConfigSpec.ConfigValue<String> MSG_LOGGED_IN;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_KICK_TIMEOUT;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_KICK_NOT_IN_GUILD;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_KICK_MISSING_ROLE;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_KICK_MISCONFIGURED;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_KICK_UNLINKED;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_LINKED_TO_OTHER;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_TOO_MANY_ACCOUNTS;
    public static final ForgeConfigSpec.ConfigValue<String> MSG_BLOCKED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("General behaviour").push("general");
        ENABLED = b.comment("Master switch. When false, nobody has to log in with Discord.")
                .define("enabled", true);
        ENABLE_IN_SINGLEPLAYER = b.comment("Also require Discord on singleplayer / LAN worlds (normally only dedicated servers).")
                .define("enableInSingleplayer", false);
        LOGIN_TIMEOUT_SECONDS = b.comment("Seconds a player has to finish the Discord login before being kicked.")
                .defineInRange("loginTimeoutSeconds", 300, 30, 3600);
        MAX_ACCOUNTS_PER_DISCORD = b.comment("How many Minecraft accounts can be linked to the same Discord account.")
                .defineInRange("maxAccountsPerDiscord", 1, 1, 100);
        b.pop();

        b.comment("Discord application (https://discord.com/developers/applications)").push("discord");
        CLIENT_ID = b.comment("OAuth2 Client ID.").define("clientId", "");
        CLIENT_SECRET = b.comment("OAuth2 Client Secret. Keep it private.").define("clientSecret", "");
        REDIRECT_URI = b.comment(
                        "Public URL Discord redirects to after login. Add it EXACTLY as is under OAuth2 > Redirects.",
                        "It must reach the callback server below, e.g. http://play.example.com:8085/callback",
                        "or https://auth.example.com/callback behind a reverse proxy. Its path is the callback path.")
                .define("redirectUri", "http://localhost:8085/callback");
        GUILD_ID = b.comment("ID of your Discord server (guild).").define("guildId", "");
        BOT_TOKEN = b.comment(
                        "Optional bot token. The bot must be in the guild and have the Server Members intent.",
                        "With it, roles are read through the bot (recommended). Without it, the mod asks players for",
                        "the 'guilds.members.read' scope and keeps their refresh token to update roles.")
                .define("botToken", "");
        b.pop();

        b.comment("Built-in HTTP server that receives the Discord redirect").push("http");
        HTTP_BIND_ADDRESS = b.comment("Address to listen on. 0.0.0.0 = all interfaces; 127.0.0.1 if behind a reverse proxy.")
                .define("bindAddress", "0.0.0.0");
        HTTP_PORT = b.comment("Port to listen on (open it in the firewall unless you use a reverse proxy).")
                .defineInRange("port", 8085, 1, 65535);
        b.pop();

        b.comment("Who can join").push("access");
        REQUIRE_GUILD_MEMBER = b.comment("Kick players whose Discord account is not in the guild.")
                .define("requireGuildMember", true);
        REQUIRED_ROLES = b.comment("Role IDs allowed to join. Having ANY of them is enough. Empty = no role needed.")
                .defineListAllowEmpty(List.of("requiredRoles"), List::of, AuthConfig::isSnowflake);
        BYPASS_FULL_ROLES = b.comment("Role IDs that can join even when the server is full. Having ANY of them is enough.")
                .defineListAllowEmpty(List.of("bypassFullRoles"), List::of, AuthConfig::isSnowflake);
        ROLE_SYNC_MINUTES = b.comment("Re-read the roles of online players every N minutes. 0 = only when they join.")
                .defineInRange("roleSyncMinutes", 10, 0, 1440);
        KICK_ON_ROLE_LOSS = b.comment("Kick online players who lose every required role during a sync.")
                .define("kickOnRoleLoss", true);
        b.pop();

        b.comment("How roles look in chat and the tab list (only visual)").push("display");
        ROLE_DISPLAY = b.comment(
                        "One entry per role: \"roleId|prefix|nameColor\". The FIRST role the player has wins, so sort by priority.",
                        "prefix uses & color codes (&c, &l...). nameColor is optional: an & code (&c) or a hex color (#FF5555).",
                        "Example: [\"111111111111111111|&4[Admin] |&c\", \"222222222222222222|&b[VIP] |#55FFFF\"]")
                .defineListAllowEmpty(List.of("roles"), List::of, o -> o instanceof String s && RoleDisplay.parse(s) != null);
        HOVER_FORMAT = b.comment("Text shown when hovering a name in chat. Placeholders: {username} {globalName} {id}. Use \\n for new lines.")
                .define("hoverFormat", "&9Discord: &f@{username}");
        SHOW_IN_TAB = b.comment("Also show the role prefix and color in the tab list.")
                .define("showInTab", true);
        b.pop();

        b.comment("Messages (& color codes; {username} = Discord username)").push("messages");
        MSG_LOGGED_IN = b.define("loggedIn", "&aSesión iniciada con Discord como &f@{username}");
        MSG_KICK_TIMEOUT = b.define("kickTimeout", "No has iniciado sesión con Discord a tiempo.");
        MSG_KICK_NOT_IN_GUILD = b.define("kickNotInGuild", "Tienes que estar en nuestro Discord para entrar.");
        MSG_KICK_MISSING_ROLE = b.define("kickMissingRole", "No tienes el rol de Discord necesario para entrar.");
        MSG_KICK_MISCONFIGURED = b.define("kickMisconfigured", "El login con Discord no está configurado en el servidor. Avisa al staff.");
        MSG_KICK_UNLINKED = b.define("kickUnlinked", "Tu vínculo con Discord se ha eliminado. Vuelve a entrar.");
        MSG_LINKED_TO_OTHER = b.define("linkedToOther", "Esta cuenta de Minecraft ya está vinculada a otro Discord. Inicia sesión con ese Discord o pide al staff que la desvincule.");
        MSG_TOO_MANY_ACCOUNTS = b.define("tooManyAccounts", "Tu Discord ya está vinculado al máximo de cuentas de Minecraft permitido.");
        MSG_BLOCKED = b.define("blocked", "&cInicia sesión con Discord antes de hacer nada.");
        b.pop();

        SPEC = b.build();
    }

    private AuthConfig() {}

    private static boolean isSnowflake(Object o) {
        return o instanceof String s && s.matches("\\d{5,25}");
    }

    public static boolean isConfigured() {
        return !CLIENT_ID.get().isBlank() && !CLIENT_SECRET.get().isBlank()
                && !REDIRECT_URI.get().isBlank() && !GUILD_ID.get().isBlank();
    }

    public static boolean useBot() {
        return !BOT_TOKEN.get().isBlank();
    }
}
