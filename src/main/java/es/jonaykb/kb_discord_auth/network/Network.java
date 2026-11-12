package es.jonaykb.kb_discord_auth.network;

import es.jonaykb.kb_discord_auth.KbDiscordAuth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Network {
    /**
     * Both sides must have exactly this version. Clients without the mod report the channel as
     * absent, which does not match, so Forge refuses them on login: the mod is required on the client.
     */
    private static final String VERSION = "4";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(KbDiscordAuth.MOD_ID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private Network() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(HelloPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelloPacket::encode).decoder(HelloPacket::decode)
                .consumerMainThread(HelloPacket::handle).add();
        CHANNEL.messageBuilder(TokenPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TokenPacket::encode).decoder(TokenPacket::decode)
                .consumerMainThread(TokenPacket::handle).add();
        CHANNEL.messageBuilder(OpenAuthPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenAuthPacket::encode).decoder(OpenAuthPacket::decode)
                .consumerMainThread(OpenAuthPacket::handle).add();
        CHANNEL.messageBuilder(AuthDonePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AuthDonePacket::encode).decoder(AuthDonePacket::decode)
                .consumerMainThread(AuthDonePacket::handle).add();
        CHANNEL.messageBuilder(NameTagColorPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(NameTagColorPacket::encode).decoder(NameTagColorPacket::decode)
                .consumerMainThread(NameTagColorPacket::handle).add();
        CHANNEL.messageBuilder(StatsPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatsPacket::encode).decoder(StatsPacket::decode)
                .consumerMainThread(StatsPacket::handle).add();
        CHANNEL.messageBuilder(StatsRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StatsRequestPacket::encode).decoder(StatsRequestPacket::decode)
                .consumerMainThread(StatsRequestPacket::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendToAll(Object packet) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }
}
