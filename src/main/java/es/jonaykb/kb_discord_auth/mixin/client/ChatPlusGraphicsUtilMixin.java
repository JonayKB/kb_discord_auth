package es.jonaykb.kb_discord_auth.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import es.jonaykb.kb_discord_auth.client.AvatarRenderer;
import es.jonaykb.kb_discord_auth.client.AvatarRenderer.Found;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/**
 * Chat Plus replaces the vanilla chat rendering, so {@link ChatComponentMixin} never runs with it. Every chat line it
 * draws (chat windows, screenshots, tab editor) goes through this method: draw the avatars there too.
 * Only applied when Chat Plus is installed.
 */
@Pseudo
@Mixin(targets = "com.ebicep.chatplus.util.GraphicsUtil", remap = false)
public abstract class ChatPlusGraphicsUtilMixin {
    @Inject(method = "drawString0(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/util/FormattedCharSequence;FFIZ)I",
            at = @At("RETURN"), require = 0, remap = false)
    private void kbDiscordAuth$drawAvatars(GuiGraphics g, FormattedCharSequence line, float x, float y, int color,
            boolean shadow, CallbackInfoReturnable<Integer> cir) {
        List<Found> found = AvatarRenderer.find(Minecraft.getInstance().font, line);
        if (found.isEmpty()) return;
        // The line is drawn at fractional coordinates: move there instead of rounding.
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        AvatarRenderer.drawAll(g, found, 0, 0, color);
        g.pose().popPose();
    }
}
