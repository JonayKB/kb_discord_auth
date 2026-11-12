package es.jonaykb.kb_discord_auth;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.client.ClientEvents;
import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.server.ServerEvents;
import es.jonaykb.kb_discord_auth.server.WebAuth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(KbDiscordAuth.MOD_ID)
public final class KbDiscordAuth {
    public static final String MOD_ID = "kb_discord_auth";
    /**
     * IMC method for other mods to answer {@code /web/api/<name>} behind the web login. Payload:
     * {@code Map.Entry<String, BiFunction<Map<UUID, String>, Map<String, String>, String>>} (name → handler), see {@link WebAuth}.
     */
    public static final String IMC_WEB_API = "web_api";
    private static final Logger LOGGER = LogUtils.getLogger();

    public KbDiscordAuth() {
        // COMMON, not SERVER: SERVER configs are synced to clients and this one holds secrets.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AuthConfig.SPEC);
        Network.register();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(KbDiscordAuth::onImc);
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MinecraftForge.EVENT_BUS.register(ClientEvents.class));
    }

    @SuppressWarnings("unchecked")
    private static void onImc(InterModProcessEvent event) {
        event.getIMCStream(IMC_WEB_API::equals).forEach(msg -> {
            try {
                var entry = (Map.Entry<String, BiFunction<Map<UUID, String>, Map<String, String>, String>>) msg.messageSupplier().get();
                WebAuth.registerApi(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                LOGGER.error("Bad web_api message from {}", msg.senderModId(), e);
            }
        });
    }
}
