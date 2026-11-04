package es.jonaykb.kb_discord_auth.mixin.client;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import es.jonaykb.kb_discord_auth.avatar.AvatarMarker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;

/** Every per-player avatar font (kb_discord_auth:avatar/<id>/<hash>) uses the one avatar font: a 10px space. */
@Mixin(Font.class)
public abstract class FontMixin {
    @Shadow @Final private Function<ResourceLocation, FontSet> fonts;

    @Inject(method = "getFontSet", at = @At("HEAD"), cancellable = true)
    private void kbDiscordAuth$avatarFont(ResourceLocation font, CallbackInfoReturnable<FontSet> cir) {
        if (AvatarMarker.isMarkerFont(font)) cir.setReturnValue(fonts.apply(AvatarMarker.FONT));
    }
}
