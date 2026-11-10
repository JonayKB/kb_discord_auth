package es.jonaykb.kb_discord_auth.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.network.AuthDonePacket;
import es.jonaykb.kb_discord_auth.network.HelloPacket;
import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.network.OpenAuthPacket;
import es.jonaykb.kb_discord_auth.network.TokenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;

/** Client state of the login. Only touched on the render thread. */
public final class ClientAuth {
    enum Mode { NONE, CHECKING, LOGIN }

    private static final Logger LOGGER = LogUtils.getLogger();

    /** While checking a saved token, wait this long before showing the screen (avoids a flash on fast checks). */
    private static final long CHECKING_GRACE_MS = 1500;

    private static Mode mode = Mode.NONE;
    private static String serverId;
    private static UUID playerId;
    private static String url = "";
    private static String message = "";
    private static long deadline;
    private static long checkingSince;
    /**
     * Screens other mods tried to open while the login was pending (difficulty or class pickers, welcome screens...).
     * They are opened in order once the login is done, so the Discord login always comes first.
     */
    private static final Deque<Screen> deferred = new ArrayDeque<>();

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
        deferred.clear();
    }

    /**
     * While the login is pending, holds back screens from other mods instead of letting them cover the login
     * screen. Vanilla and Forge screens (pause, inventory, disconnect...) are left alone.
     */
    static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next == null || next instanceof DiscordLoginScreen) return;
        Class<?> type = next.getClass();
        // Only one pending copy per screen type: a newer one replaces it, and if it opens on its own
        // (e.g. a picker that chains into the next one) the held-back copy is no longer needed.
        deferred.removeIf(s -> s.getClass() == type);
        if (mode == Mode.NONE || isBuiltIn(type)) return;
        deferred.addLast(next);
        event.setCanceled(true);
        LOGGER.debug("Discord login pending ({}): holding back {}", mode, type.getName());
    }

    private static boolean isBuiltIn(Class<?> type) {
        String name = type.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.");
    }

    /** Keeps the login screen up: if something closes it, open it again. Afterwards, opens the held-back screens. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (mode == Mode.NONE) {
            Screen next = deferred.pollFirst();
            if (next != null) {
                LOGGER.debug("Discord login done: opening held-back {}", next.getClass().getName());
                mc.setScreen(next);
            }
        } else if (mode == Mode.LOGIN) {
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
            LOGGER.debug("Showing the Discord login screen ({})", mode);
            mc.setScreen(new DiscordLoginScreen());
        }
    }
}
