package es.jonaykb.kb_discord_auth;

import es.jonaykb.kb_discord_auth.client.ClientEvents;
import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.server.ServerEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(KbDiscordAuth.MOD_ID)
public final class KbDiscordAuth {
    public static final String MOD_ID = "kb_discord_auth";

    public KbDiscordAuth() {
        // COMMON, not SERVER: SERVER configs are synced to clients and this one holds secrets.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AuthConfig.SPEC);
        Network.register();
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MinecraftForge.EVENT_BUS.register(ClientEvents.class));
    }
}
