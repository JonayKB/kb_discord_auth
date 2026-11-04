package es.jonaykb.kb_discord_auth.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import es.jonaykb.kb_discord_auth.client.AvatarRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;

/** Draws the Discord avatars of each chat line over their markers. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int kbDiscordAuth$drawLine(GuiGraphics g, Font font, FormattedCharSequence line, int x, int y, int color) {
        int result = g.drawString(font, line, x, y, color);
        AvatarRenderer.drawAll(g, AvatarRenderer.find(font, line), x, y, color);
        return result;
    }
}
