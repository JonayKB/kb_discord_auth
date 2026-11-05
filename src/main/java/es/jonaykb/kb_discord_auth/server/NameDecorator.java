package es.jonaykb.kb_discord_auth.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import es.jonaykb.kb_discord_auth.avatar.AvatarMarker;
import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.config.AuthConfig.DisplayMode;
import es.jonaykb.kb_discord_auth.config.RoleDisplay;
import es.jonaykb.kb_discord_auth.discord.DiscordApi.GuildRole;
import es.jonaykb.kb_discord_auth.server.LinkStore.LinkRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/** Builds "[Role] Name" for chat and the tab list from the player's Discord roles. Only visual. */
final class NameDecorator {
    /** One role that is shown: its name (for {roles}), its prefix and the color it gives the name. */
    record Shown(String roleName, Component prefix, @Nullable TextColor nameColor) {}

    private NameDecorator() {}

    static MutableComponent decorate(LinkRecord r, Map<String, GuildRole> guild, Component name, boolean forTab) {
        boolean showRoles = !forTab || AuthConfig.SHOW_IN_TAB.get();
        List<Shown> shown = showRoles ? shownRoles(r.roles, guild) : List.of();

        MutableComponent out = Component.empty();
        if (forTab ? AuthConfig.AVATARS_IN_TAB.get() : AuthConfig.AVATARS_IN_CHAT.get()) {
            MutableComponent avatar = AvatarMarker.marker(r.discordId, r.discordAvatar);
            if (avatar != null) out.append(avatar);
        }
        for (Shown s : shown) {
            if (!s.prefix().getString().isEmpty()) out.append(s.prefix());
        }
        TextColor nameColor = nameColor(shown);
        MutableComponent coloredName = name.copy();
        if (nameColor != null) coloredName.withStyle(st -> st.withColor(nameColor));
        out.append(coloredName);

        if (!forTab) {
            String roles = shown.stream().map(Shown::roleName).filter(n -> !n.isEmpty()).collect(Collectors.joining(", "));
            String text = AuthManager.placeholders(AuthConfig.HOVER_FORMAT.get(), r).replace("{roles}", roles).replace("\\n", "\n");
            Component hover = Component.literal(RoleDisplay.legacy(text));
            // A child's hover wins over the vanilla entity hover that Minecraft puts around the whole name.
            out.withStyle(st -> st.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
        }
        return out;
    }

    /** Color of the name tag above the head: the same name color as in chat, without the prefix. */
    @Nullable
    static TextColor nameTagColor(LinkRecord r, Map<String, GuildRole> guild) {
        return nameColor(shownRoles(r.roles, guild));
    }

    /** Like Discord: the name takes the color of the highest shown role that has one. */
    @Nullable
    private static TextColor nameColor(List<Shown> shown) {
        return shown.stream().map(Shown::nameColor).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** The roles to show, highest first, after exclusions and the HIGHEST/ALL mode. */
    static List<Shown> shownRoles(Collection<String> playerRoles, Map<String, GuildRole> guild) {
        Set<String> excluded = new HashSet<>(AuthConfig.EXCLUDED_ROLES.get());
        excluded.add(AuthConfig.GUILD_ID.get()); // @everyone has the guild's id

        Map<String, RoleDisplay> overrides = new LinkedHashMap<>();
        for (RoleDisplay d : RoleDisplay.all()) overrides.putIfAbsent(d.roleId(), d);

        List<Shown> out = new ArrayList<>();
        if (AuthConfig.AUTOMATIC_ROLES.get() && AuthConfig.useBot() && !guild.isEmpty()) {
            List<GuildRole> mine = playerRoles.stream()
                    .map(guild::get)
                    .filter(Objects::nonNull)
                    .filter(role -> !excluded.contains(role.id()))
                    .filter(role -> role.color() != 0 || AuthConfig.INCLUDE_UNCOLORED_ROLES.get() || overrides.containsKey(role.id()))
                    // Discord order: higher position first; on a tie, the older role (lower id) first.
                    .sorted(Comparator.comparingInt(GuildRole::position).reversed()
                            .thenComparing(role -> Long.parseLong(role.id())))
                    .toList();
            for (GuildRole role : mine) {
                TextColor roleColor = role.color() != 0 ? TextColor.fromRgb(role.color()) : null;
                RoleDisplay o = overrides.get(role.id());
                if (o != null) {
                    out.add(new Shown(role.name(), Component.literal(RoleDisplay.legacy(o.prefix())),
                            o.nameColor() != null ? o.nameColor() : roleColor));
                } else {
                    String text = RoleDisplay.legacy(AuthConfig.AUTO_PREFIX_FORMAT.get().replace("{name}", role.name()));
                    MutableComponent prefix = Component.literal(text);
                    if (roleColor != null) prefix.withStyle(st -> st.withColor(roleColor));
                    out.add(new Shown(role.name(), prefix, roleColor));
                }
            }
        } else {
            // Only the configured roles, in config order.
            for (RoleDisplay d : overrides.values()) {
                if (!playerRoles.contains(d.roleId()) || excluded.contains(d.roleId())) continue;
                GuildRole role = guild.get(d.roleId());
                out.add(new Shown(role != null ? role.name() : "", Component.literal(RoleDisplay.legacy(d.prefix())), d.nameColor()));
            }
        }

        int max = AuthConfig.MODE.get() == DisplayMode.HIGHEST ? 1 : AuthConfig.MAX_ROLES.get();
        return max > 0 && out.size() > max ? out.subList(0, max) : out;
    }
}
