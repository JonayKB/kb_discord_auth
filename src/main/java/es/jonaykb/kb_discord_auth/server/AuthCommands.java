package es.jonaykb.kb_discord_auth.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import es.jonaykb.kb_discord_auth.server.LinkStore.LinkRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /discordauth info &lt;player&gt;   – shows the linked Discord account and roles
 * /discordauth unlink &lt;player&gt; – removes the link (kicks them if online)
 * /discordauth resync          – re-reads the roles of everyone online
 */
final class AuthCommands {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private AuthCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("discordauth")
                .requires(src -> src.hasPermission(3))
                .then(Commands.literal("info")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(linkedNames(), b))
                                .executes(AuthCommands::info)))
                .then(Commands.literal("unlink")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(linkedNames(), b))
                                .executes(AuthCommands::unlink)))
                .then(Commands.literal("resync").executes(AuthCommands::resync)));
    }

    private static Iterable<String> linkedNames() {
        AuthManager auth = AuthManager.get();
        return auth == null ? java.util.List.of() : auth.store().names();
    }

    private static UUID resolve(CommandSourceStack src, String name) {
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        AuthManager auth = AuthManager.get();
        return auth == null ? null : auth.store().findByName(name);
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        AuthManager auth = AuthManager.get();
        String name = StringArgumentType.getString(ctx, "player");
        UUID uuid = resolve(src, name);
        LinkRecord r = auth == null || uuid == null ? null : auth.store().get(uuid);
        if (r == null) {
            src.sendFailure(Component.literal(name + " no tiene Discord vinculado."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "§9" + r.minecraftName + "§r → §f@" + r.discordUsername + " §7(" + r.discordGlobalName + ", " + r.discordId + ")\n"
                        + "§7En el servidor de Discord: §f" + (r.inGuild ? "sí" : "no") + "\n"
                        + "§7Roles: §f" + (r.roles.isEmpty() ? "-" : String.join(", ", r.roles)) + "\n"
                        + "§7Vinculado: §f" + DATE.format(Instant.ofEpochMilli(r.linkedAt))
                        + " §7· roles leídos: §f" + (r.rolesSyncedAt == 0 ? "-" : DATE.format(Instant.ofEpochMilli(r.rolesSyncedAt)))),
                false);
        return 1;
    }

    private static int unlink(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        AuthManager auth = AuthManager.get();
        String name = StringArgumentType.getString(ctx, "player");
        UUID uuid = resolve(src, name);
        LinkRecord r = auth == null || uuid == null ? null : auth.unlink(uuid);
        if (r == null) {
            src.sendFailure(Component.literal(name + " no tiene Discord vinculado."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Desvinculado " + r.minecraftName + " de @" + r.discordUsername + "."), true);
        return 1;
    }

    private static int resync(CommandContext<CommandSourceStack> ctx) {
        AuthManager auth = AuthManager.get();
        if (auth == null) return 0;
        int n = auth.resyncOnline();
        ctx.getSource().sendSuccess(() -> Component.literal("Releyendo los roles de " + n + " jugador(es)."), true);
        return n;
    }
}
