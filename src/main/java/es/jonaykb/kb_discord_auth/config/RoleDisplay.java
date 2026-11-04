package es.jonaykb.kb_discord_auth.config;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;

/** One "roleId|prefix|nameColor" entry of the display config. */
public record RoleDisplay(String roleId, String prefix, @Nullable TextColor nameColor) {

    @Nullable
    public static RoleDisplay parse(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 2 || parts.length > 3) return null;
        String roleId = parts[0].trim();
        if (!roleId.matches("\\d{5,25}")) return null;
        TextColor color = null;
        if (parts.length == 3 && !parts[2].isBlank()) {
            color = parseColor(parts[2].trim());
            if (color == null) return null;
        }
        return new RoleDisplay(roleId, parts[1], color);
    }

    @Nullable
    private static TextColor parseColor(String s) {
        if (s.startsWith("#")) return TextColor.parseColor(s);
        if ((s.startsWith("&") || s.startsWith("§")) && s.length() == 2) {
            ChatFormatting f = ChatFormatting.getByCode(s.charAt(1));
            return f != null && f.isColor() ? TextColor.fromLegacyFormat(f) : null;
        }
        ChatFormatting f = ChatFormatting.getByName(s);
        return f != null && f.isColor() ? TextColor.fromLegacyFormat(f) : null;
    }

    /** The configured entries, in priority order. Invalid entries are skipped by the config validator. */
    public static List<RoleDisplay> all() {
        List<RoleDisplay> out = new ArrayList<>();
        for (String raw : AuthConfig.ROLE_DISPLAY.get()) {
            RoleDisplay d = parse(raw);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** Turns &-codes into §-codes, which the client renders inside literal text. */
    public static String legacy(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && ChatFormatting.getByCode(s.charAt(i + 1)) != null) {
                out.append('§');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
