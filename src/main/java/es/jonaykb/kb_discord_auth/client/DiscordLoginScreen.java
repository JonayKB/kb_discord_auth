package es.jonaykb.kb_discord_auth.client;

import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Blocking screen shown until the player logs in with Discord. It can't be closed with Esc. */
public class DiscordLoginScreen extends Screen {
    private static final int BLURPLE = 0x5865F2;
    private static final int TEXT = 0xFFFFFF;
    private static final int MUTED = 0xA0A0A0;
    private static final int ERROR = 0xFF5555;
    private static final int OK = 0x55FF55;

    private Button loginButton;
    private Button copyButton;
    private boolean opened;
    private int copiedTicks;

    public DiscordLoginScreen() {
        super(Component.translatable("kb_discord_auth.screen.title"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height / 2 + 10;
        loginButton = addRenderableWidget(Button.builder(Component.translatable("kb_discord_auth.screen.login"), b -> {
            Util.getPlatform().openUri(ClientAuth.url());
            opened = true;
        }).bounds(cx - 100, y, 200, 20).build());
        copyButton = addRenderableWidget(Button.builder(Component.translatable("kb_discord_auth.screen.copy"), b -> {
            minecraft.keyboardHandler.setClipboard(ClientAuth.url());
            copiedTicks = 60;
        }).bounds(cx - 100, y + 24, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("menu.disconnect"), b -> disconnect(b))
                .bounds(cx + 2, y + 24, 98, 20).build());
        refresh();
    }

    /** Called when the server sends a new state (e.g. a new link after an error). */
    void refresh() {
        boolean ready = ClientAuth.mode() == ClientAuth.Mode.LOGIN && !ClientAuth.url().isEmpty();
        if (loginButton != null) loginButton.active = ready;
        if (copyButton != null) copyButton.active = ready;
        if (!ClientAuth.message().isEmpty()) opened = false;
    }

    @Override
    public void tick() {
        if (copiedTicks > 0) copiedTicks--;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // In the waiting room there is nothing to see: cover it, like a loading screen.
        if (ClientAuth.inLimbo()) renderDirtBackground(g);
        else renderBackground(g);
        int cx = width / 2;
        int top = height / 2 - 70;

        g.fill(cx - 110, top - 6, cx + 110, top - 4, 0xFF000000 | BLURPLE);
        g.drawCenteredString(font, title, cx, top + 4, TEXT);

        int y = top + 22;
        if (ClientAuth.mode() == ClientAuth.Mode.CHECKING) {
            y = drawWrapped(g, Component.translatable("kb_discord_auth.screen.checking"), cx, y, MUTED);
        } else {
            y = drawWrapped(g, Component.translatable("kb_discord_auth.screen.explain"), cx, y, TEXT);
            Component hint = opened
                    ? Component.translatable("kb_discord_auth.screen.waiting")
                    : Component.translatable("kb_discord_auth.screen.hint");
            y = drawWrapped(g, hint, cx, y + 2, MUTED);
            if (!ClientAuth.message().isEmpty()) {
                drawWrapped(g, Component.literal(ClientAuth.message()), cx, y + 2, ERROR);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);

        int below = height / 2 + 60;
        if (copiedTicks > 0) {
            g.drawCenteredString(font, Component.translatable("kb_discord_auth.screen.copied"), cx, below, OK);
        } else if (ClientAuth.mode() == ClientAuth.Mode.LOGIN) {
            long left = Math.max(0, (ClientAuth.deadline() - System.currentTimeMillis()) / 1000);
            g.drawCenteredString(font, Component.translatable("kb_discord_auth.screen.time_left",
                    String.format("%d:%02d", left / 60, left % 60)), cx, below, MUTED);
        }
    }

    private int drawWrapped(GuiGraphics g, Component text, int cx, int y, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.min(300, width - 32));
        for (FormattedCharSequence line : lines) {
            g.drawCenteredString(font, line, cx, y, color);
            y += font.lineHeight + 2;
        }
        return y;
    }

    private void disconnect(Button button) {
        button.active = false;
        boolean local = minecraft.isLocalServer();
        ClientAuth.reset();
        if (minecraft.level != null) minecraft.level.disconnect();
        if (local) {
            minecraft.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            minecraft.clearLevel();
        }
        TitleScreen title = new TitleScreen();
        minecraft.setScreen(local ? title : new JoinMultiplayerScreen(title));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
