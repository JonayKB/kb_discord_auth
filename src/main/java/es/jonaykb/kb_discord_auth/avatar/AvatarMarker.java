package es.jonaykb.kb_discord_auth.avatar;

import javax.annotation.Nullable;

import es.jonaykb.kb_discord_auth.KbDiscordAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * The Discord avatar travels inside the name itself: a space whose font is
 * {@code kb_discord_auth:avatar/<discordId>/<avatarHash|none>}. The client maps every such font to
 * {@code kb_discord_auth:avatar}, whose space is {@link #WIDTH} pixels wide, and draws the picture over it.
 * So wrapping, hover and click positions in chat stay right, and no extra packet is needed.
 * Clients only ever see a 10px gap if they could not draw it; the console shows a plain space.
 */
public final class AvatarMarker {
    public static final ResourceLocation FONT = new ResourceLocation(KbDiscordAuth.MOD_ID, "avatar");
    /** Advance of the marker space, in GUI pixels: 1px gap + 8px picture + 1px gap. */
    public static final int WIDTH = 10;
    private static final String PREFIX = "avatar/";

    public record Avatar(String discordId, @Nullable String hash) {
        public String key() {
            return discordId + "/" + (hash == null ? "none" : hash);
        }
    }

    private AvatarMarker() {}

    @Nullable
    public static MutableComponent marker(@Nullable String discordId, @Nullable String hash) {
        if (discordId == null || !discordId.matches("\\d{5,25}")) return null;
        String h = hash != null && hash.matches("[a-z0-9_]{1,64}") ? hash : "none";
        ResourceLocation font = new ResourceLocation(KbDiscordAuth.MOD_ID, PREFIX + discordId + "/" + h);
        return Component.literal(" ").withStyle(Style.EMPTY.withFont(font).withBold(false));
    }

    public static boolean isMarkerFont(ResourceLocation font) {
        return font.getNamespace().equals(KbDiscordAuth.MOD_ID) && font.getPath().startsWith(PREFIX);
    }

    @Nullable
    public static Avatar parse(ResourceLocation font) {
        if (!isMarkerFont(font)) return null;
        String[] parts = font.getPath().substring(PREFIX.length()).split("/");
        if (parts.length != 2 || !parts[0].matches("\\d{5,25}") || !parts[1].matches("[a-z0-9_]{1,64}")) return null;
        return new Avatar(parts[0], parts[1].equals("none") ? null : parts[1]);
    }
}
