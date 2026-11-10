package es.jonaykb.kb_discord_auth.client;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ClientAuth.tick();
    }

    /** Low priority so it sees the screen that will really open, after other mods had their say. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        ClientAuth.onScreenOpening(event);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAuth.reset();
        AvatarTextures.clear();
        ClientNameTags.clear();
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        ClientNameTags.onRenderNameTag(event);
    }
}
