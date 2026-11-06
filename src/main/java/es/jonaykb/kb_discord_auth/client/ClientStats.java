package es.jonaykb.kb_discord_auth.client;

import es.jonaykb.kb_discord_auth.network.StatsPacket;
import net.minecraft.client.Minecraft;

/** Receives the stats screen data from the server. */
public final class ClientStats {
    private ClientStats() {}

    public static void onData(StatsPacket data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StatsScreen screen) {
            screen.setData(data);
        } else if (data.open() && !(mc.screen instanceof DiscordLoginScreen)) {
            mc.setScreen(new StatsScreen(data));
        }
    }
}
