package es.jonaykb.kb_discord_auth.client;

import java.util.UUID;

import es.jonaykb.kb_discord_auth.network.AuthDonePacket;
import es.jonaykb.kb_discord_auth.network.HelloPacket;
import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.network.OpenAuthPacket;
import es.jonaykb.kb_discord_auth.network.TokenPacket;
import net.minecraft.client.Minecraft;

/** Client state of the login. Only touched on the render thread. */
public final class ClientAuth {
    enum Mode { NONE, CHECKING, LOGIN }

    /** While checking a saved token, wait this long before showing the screen (avoids a flash on fast checks). */
    private static final long CHECKING_GRACE_MS = 1500;

    private static Mode mode = Mode.NONE;
    private static String serverId;
    private static UUID playerId;
    private static String url = "";
    private static String message = "";
    private static long deadline;
    private static long checkingSince;

    private ClientAuth() {}

    static Mode mode() { return mode; }
    static String url() { return url; }
    static String message() { return message; }
    static long deadline() { return deadline; }

    private static String key() {
        return serverId + "/" + playerId;
    }

    public static void onHello(HelloPacket p) {
        serverId = p.serverId();
        playerId = p.playerId();
        mode = Mode.CHECKING;
        url = "";
        message = "";
        checkingSince = System.currentTimeMillis();
        // Don't open the screen yet: if the token is valid the server answers quickly and nothing flashes.
        // tick() opens it if the check takes longer than CHECKING_GRACE_MS.
        refreshIfOpen();
        String token = ClientTokenStore.get(key());
        Network.CHANNEL.sendToServer(new TokenPacket(token == null ? "" : token));
    }

    public static void onOpenAuth(OpenAuthPacket p) {
        mode = Mode.LOGIN;
        url = p.url();
        message = p.message();
        deadline = System.currentTimeMillis() + p.secondsLeft() * 1000L;
        show();
    }

    public static void onDone(AuthDonePacket p) {
        if (!p.token().isEmpty() && serverId != null) ClientTokenStore.put(key(), p.token());
        mode = Mode.NONE;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DiscordLoginScreen) mc.setScreen(null);
    }

    static void reset() {
        mode = Mode.NONE;
        serverId = null;
        playerId = null;
        url = "";
        message = "";
        checkingSince = 0;
    }

    /** Keeps the login screen up: if something closes it, open it again. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (mode == Mode.LOGIN) {
            show();
        } else if (mode == Mode.CHECKING && System.currentTimeMillis() - checkingSince >= CHECKING_GRACE_MS) {
            show();
        }
    }

    private static void refreshIfOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DiscordLoginScreen screen) screen.refresh();
    }

    private static void show() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DiscordLoginScreen screen) {
            screen.refresh();
        } else {
            mc.setScreen(new DiscordLoginScreen());
        }
    }
}
