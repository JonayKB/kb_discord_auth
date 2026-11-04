package es.jonaykb.kb_discord_auth.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import es.jonaykb.kb_discord_auth.client.AvatarRenderer;
import es.jonaykb.kb_discord_auth.client.AvatarRenderer.Found;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Tab list: [avatar][skin head][name]. The name starts with a 10px avatar marker. When the skin head is shown
 * (online-mode servers), the head moves into the marker's gap and the avatar takes the head's place.
 * Otherwise the avatar is simply drawn over the marker.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Unique
    private record PendingFace(GuiGraphics g, ResourceLocation skin, int x, int y, int size, boolean hat, boolean upsideDown) {
        void draw(int atX) {
            PlayerFaceRenderer.draw(g, skin, atX, y, size, hat, upsideDown);
        }
    }

    @Unique
    private PendingFace kbDiscordAuth$face;

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerFaceRenderer;draw(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;IIIZZ)V"))
    private void kbDiscordAuth$deferFace(GuiGraphics g, ResourceLocation skin, int x, int y, int size, boolean hat, boolean upsideDown) {
        // Drawn together with the name, once we know whether it has an avatar.
        kbDiscordAuth$face = new PendingFace(g, skin, x, y, size, hat, upsideDown);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
    private int kbDiscordAuth$drawName(GuiGraphics g, Font font, Component name, int x, int y, int color) {
        List<Found> found = AvatarRenderer.find(font, name.getVisualOrderText());
        PendingFace face = kbDiscordAuth$face;
        kbDiscordAuth$face = null;
        if (face != null) {
            if (!found.isEmpty() && found.get(0).x() == 0) {
                // [avatar where the head was][head in the marker's gap][name]
                AvatarRenderer.draw(g, found.get(0).avatar(), face.x(), y, color);
                face.draw(x);
                found = found.subList(1, found.size());
            } else {
                face.draw(face.x());
            }
        }
        int result = g.drawString(font, name, x, y, color);
        AvatarRenderer.drawAll(g, found, x, y, color);
        return result;
    }
}
