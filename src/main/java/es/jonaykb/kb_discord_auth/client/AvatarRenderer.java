package es.jonaykb.kb_discord_auth.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import es.jonaykb.kb_discord_auth.avatar.AvatarMarker;
import es.jonaykb.kb_discord_auth.avatar.AvatarMarker.Avatar;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** Draws Discord avatars over the avatar markers of a line of text. */
public final class AvatarRenderer {
    /** A marker found in a line: its x offset from the start of the line, in GUI pixels. */
    public record Found(Avatar avatar, int x) {}

    private AvatarRenderer() {}

    public static List<Found> find(Font font, FormattedCharSequence text) {
        List<Found> out = new ArrayList<>();
        float[] x = {0};
        text.accept((index, style, codepoint) -> {
            Avatar avatar = AvatarMarker.parse(style.getFont());
            if (avatar != null) out.add(new Found(avatar, Math.round(x[0])));
            x[0] += font.getSplitter().stringWidth(FormattedCharSequence.codepoint(codepoint, style));
            return true;
        });
        return out;
    }

    /** Draws every avatar of the line, which was drawn at (x, y) with the given ARGB text color. */
    public static void drawAll(GuiGraphics g, List<Found> found, int x, int y, int color) {
        for (Found f : found) draw(g, f.avatar(), x + f.x() + 1, y, color);
    }

    /** Draws one 8x8 avatar. The alpha comes from the text color, so it fades out with the chat. */
    public static void draw(GuiGraphics g, Avatar avatar, int x, int y, int color) {
        ResourceLocation texture = AvatarTextures.get(avatar);
        if (texture == null) return;
        float alpha = alpha(color);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        g.blit(texture, x, y, 8, 8, 0f, 0f, 32, 32, 32, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Same rule as Font: an alpha of almost 0 means "opaque". */
    private static float alpha(int color) {
        return (color & 0xFC000000) == 0 ? 1f : (color >>> 24) / 255f;
    }
}
