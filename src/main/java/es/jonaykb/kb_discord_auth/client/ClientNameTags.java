package es.jonaykb.kb_discord_auth.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import es.jonaykb.kb_discord_auth.network.NameTagColorPacket;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderNameTagEvent;

/** Role colors of the name tags above players' heads, sent by the server. Only touched on the render thread. */
public final class ClientNameTags {
    private static final Map<UUID, TextColor> COLORS = new HashMap<>();

    private ClientNameTags() {}

    public static void set(UUID player, int color) {
        if (color == NameTagColorPacket.NONE) {
            COLORS.remove(player);
        } else {
            COLORS.put(player, TextColor.fromRgb(color));
        }
    }

    static void clear() {
        COLORS.clear();
    }

    /** Only the color: parts that already have their own (e.g. a team prefix) keep it. */
    static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        TextColor color = COLORS.get(player.getUUID());
        if (color == null) return;
        event.setContent(event.getContent().copy().withStyle(st -> st.withColor(color)));
    }
}
