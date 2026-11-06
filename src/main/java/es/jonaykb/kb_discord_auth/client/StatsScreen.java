package es.jonaykb.kb_discord_auth.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.network.StatsPacket;
import es.jonaykb.kb_discord_auth.network.StatsRequestPacket;
import es.jonaykb.kb_discord_auth.stats.StatKind;
import es.jonaykb.kb_discord_auth.stats.StatsRow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * /stats: the server rankings (top 10 of each stat plus your own position), like /ranking in Discord,
 * and a tab with all your stats. Everything is chosen with buttons.
 */
public class StatsScreen extends Screen {
    private static final int BLURPLE = 0x5865F2;
    private static final int TEXT = 0xFFFFFF;
    private static final int MUTED = 0xA0A0A0;
    private static final int DIM = 0x707070;
    private static final int SELF = 0xFFFF55;
    private static final int[] MEDALS = {0xFFD700, 0xC0C0C0, 0xCD7F32};

    private static final int PANEL_W = 330;
    private static final int PANEL_H = 214;
    private static final int SIDEBAR_W = 104;
    private static final int ROW_H = 12;

    private StatsPacket data;
    /** {@code null} = "My stats". */
    @Nullable private StatKind selected = StatKind.PLAY_TIME;
    private final Map<StatKind, Button> tabs = new EnumMap<>(StatKind.class);
    private Button mineButton;
    private Button refreshButton;

    public StatsScreen(StatsPacket data) {
        super(Component.translatable("kb_discord_auth.stats.title"));
        this.data = data;
    }

    void setData(StatsPacket data) {
        this.data = data;
        if (refreshButton != null) refreshButton.active = true;
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return (height - PANEL_H) / 2;
    }

    @Override
    protected void init() {
        int x = left() + 8;
        int y = top() + 24;
        tabs.clear();
        for (StatKind kind : StatKind.values()) {
            tabs.put(kind, addRenderableWidget(Button.builder(Component.translatable(kind.translationKey()), b -> select(kind))
                    .bounds(x, y, SIDEBAR_W, 20).build()));
            y += 22;
        }
        y += 4;
        mineButton = addRenderableWidget(Button.builder(Component.translatable("kb_discord_auth.stats.mine"), b -> select(null))
                .bounds(x, y, SIDEBAR_W, 20).build());

        int bottom = top() + PANEL_H - 28;
        refreshButton = addRenderableWidget(Button.builder(Component.translatable("kb_discord_auth.stats.refresh"), b -> {
            b.active = false;
            Network.CHANNEL.sendToServer(new StatsRequestPacket());
        }).bounds(x, bottom, (SIDEBAR_W - 2) / 2, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x + (SIDEBAR_W + 2) / 2, bottom, (SIDEBAR_W - 2) / 2, 20).build());
        updateTabs();
    }

    private void select(@Nullable StatKind kind) {
        selected = kind;
        updateTabs();
    }

    /** The open tab's button is disabled, which also marks it as the current one. */
    private void updateTabs() {
        tabs.forEach((kind, button) -> button.active = kind != selected);
        if (mineButton != null) mineButton.active = selected != null;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int l = left();
        int t = top();
        g.fill(l, t, l + PANEL_W, t + PANEL_H, 0xC0101010);
        g.fill(l, t, l + PANEL_W, t + 2, 0xFF000000 | BLURPLE);
        g.drawCenteredString(font, title, l + PANEL_W / 2, t + 8, TEXT);
        g.fill(l + SIDEBAR_W + 14, t + 24, l + SIDEBAR_W + 15, t + PANEL_H - 8, 0x40FFFFFF);

        int cx = l + SIDEBAR_W + 22;
        int right = l + PANEL_W - 10;
        if (selected == null) {
            renderMine(g, cx, t + 24, right);
        } else {
            renderRanking(g, selected, cx, t + 24, right);
        }
        g.drawString(font, Component.translatable("kb_discord_auth.stats.players", data.total()), cx, t + PANEL_H - 16, DIM, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderRanking(GuiGraphics g, StatKind kind, int x, int y, int right) {
        g.drawString(font, Component.translatable("kb_discord_auth.stats.ranking", Component.translatable(kind.translationKey())),
                x, y + 4, BLURPLE | 0x404040, false);
        y += 20;

        List<StatsRow> sorted = new ArrayList<>(data.rows());
        sorted.sort(kind.order(StatsRow::stats, StatsRow::name));
        int shown = Math.min(sorted.size(), es.jonaykb.kb_discord_auth.stats.StatsLeaderboard.TOP);
        boolean selfShown = false;
        for (int i = 0; i < shown; i++) {
            StatsRow r = sorted.get(i);
            boolean self = r.uuid().equals(data.self());
            selfShown |= self;
            row(g, i + 1, r, kind, self, x, y, right);
            y += ROW_H;
        }
        if (!selfShown) {
            StatsRow self = self();
            if (self != null) {
                g.drawString(font, "…", x, y, DIM, false);
                y += ROW_H;
                row(g, data.ranks()[kind.ordinal()], self, kind, true, x, y, right);
            }
        }
    }

    private void row(GuiGraphics g, int rank, StatsRow r, StatKind kind, boolean self, int x, int y, int right) {
        int rankColor = rank <= MEDALS.length ? MEDALS[rank - 1] : MUTED;
        g.drawString(font, "#" + rank, x, y, rankColor, false);
        String value = kind.format(r.stats());
        int valueW = font.width(value);
        int nameX = x + 26;
        String name = font.plainSubstrByWidth(r.name(), right - valueW - 8 - nameX);
        g.drawString(font, name, nameX, y, self ? SELF : TEXT, false);
        g.drawString(font, value, right - valueW, y, self ? SELF : TEXT, false);
    }

    private void renderMine(GuiGraphics g, int x, int y, int right) {
        StatsRow self = self();
        g.drawString(font, Component.translatable("kb_discord_auth.stats.mine"), x, y + 4, BLURPLE | 0x404040, false);
        if (self == null) return;
        y += 20;
        for (StatKind kind : StatKind.values()) {
            g.drawString(font, Component.translatable(kind.translationKey()), x, y, MUTED, false);
            String value = kind.format(self.stats());
            g.drawString(font, value, right - font.width(value), y, TEXT, false);
            y += ROW_H;
            int rank = data.ranks()[kind.ordinal()];
            Component pos = Component.translatable("kb_discord_auth.stats.rank", rank, data.total());
            g.drawString(font, pos, right - font.width(pos), y, rank >= 1 && rank <= MEDALS.length ? MEDALS[rank - 1] : DIM, false);
            y += ROW_H + 4;
        }
    }

    @Nullable
    private StatsRow self() {
        for (StatsRow r : data.rows()) {
            if (r.uuid().equals(data.self())) return r;
        }
        return null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
