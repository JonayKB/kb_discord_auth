package es.jonaykb.kb_discord_auth.stats;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import es.jonaykb.kb_discord_auth.network.Network;
import es.jonaykb.kb_discord_auth.server.AuthManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /stats          – opens the stats screen (own stats and rankings, with buttons)
 * /stats &lt;player&gt; – shows that player's stats in the chat
 */
public final class StatsCommand {
    private StatsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stats")
                .executes(StatsCommand::openScreen)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(), b))
                        .executes(StatsCommand::showPlayer)));
    }

    private static Iterable<String> names() {
        StatsManager m = StatsManager.get();
        return m == null ? List.of() : m.leaderboard().names();
    }

    private static int openScreen(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable("kb_discord_auth.stats.console"));
            return 0;
        }
        if (StatsManager.get() == null) {
            src.sendFailure(Component.translatable("kb_discord_auth.stats.unavailable"));
            return 0;
        }
        sendScreen(player, true);
        return 1;
    }

    /** Sends the stats screen data; with {@code open} false it only refreshes a screen already open. */
    public static void sendScreen(ServerPlayer player, boolean open) {
        StatsManager m = StatsManager.get();
        AuthManager auth = AuthManager.get();
        if (m == null || (auth != null && auth.isRestricted(player))) return;
        m.leaderboard().withRows(rows -> {
            if (player.hasDisconnected()) return;
            Network.sendTo(player, StatsLeaderboard.packetFor(player, rows, open));
        });
    }

    private static int showPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        StatsManager m = StatsManager.get();
        if (m == null) {
            src.sendFailure(Component.translatable("kb_discord_auth.stats.unavailable"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(name);
        m.leaderboard().withRows(rows -> {
            StatsRow row = null;
            for (StatsRow r : rows) {
                if (online != null ? r.uuid().equals(online.getUUID()) : r.name().equalsIgnoreCase(name)) {
                    // Several files may share a name (offline-mode servers): keep the one with the most time.
                    if (row == null || r.stats().playTimeSeconds() > row.stats().playTimeSeconds()) row = r;
                }
            }
            if (row == null) {
                src.sendFailure(Component.translatable("kb_discord_auth.stats.not_found", name));
                return;
            }
            Component text = card(row, StatsLeaderboard.ranks(row, rows), rows.size());
            src.sendSuccess(() -> text, false);
        });
        return 1;
    }

    private static Component card(StatsRow row, int[] ranks, int total) {
        MutableComponent out = Component.translatable("kb_discord_auth.stats.chat_header", row.name())
                .withStyle(ChatFormatting.BLUE);
        for (StatKind kind : StatKind.values()) {
            out.append(Component.literal("\n"))
                    .append(Component.translatable(kind.translationKey()).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(kind.format(row.stats())).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" "))
                    .append(Component.translatable("kb_discord_auth.stats.rank", ranks[kind.ordinal()], total)
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }
}
