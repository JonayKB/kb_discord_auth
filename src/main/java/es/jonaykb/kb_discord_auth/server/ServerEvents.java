package es.jonaykb.kb_discord_auth.server;

import javax.annotation.Nullable;

import es.jonaykb.kb_discord_auth.config.AuthConfig;
import es.jonaykb.kb_discord_auth.config.RoleDisplay;
import es.jonaykb.kb_discord_auth.stats.StatsCommand;
import es.jonaykb.kb_discord_auth.stats.StatsManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge events on the server: lifecycle, login, names, and blocking players who haven't logged in yet. */
public final class ServerEvents {
    private ServerEvents() {}

    @Nullable
    private static AuthManager auth() {
        return AuthManager.get();
    }

    private static boolean restricted(@Nullable Entity entity) {
        AuthManager auth = auth();
        return auth != null && entity instanceof Player p && auth.isRestricted(p);
    }

    private static void warn(Player player) {
        player.displayClientMessage(Component.literal(RoleDisplay.legacy(AuthConfig.MSG_BLOCKED.get())), true);
    }

    // ───────── lifecycle ─────────

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AuthManager.start(event.getServer());
        StatsManager.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StatsManager.stop();
        AuthManager.stop();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher());
        StatsCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        AuthManager auth = auth();
        if (event.phase != TickEvent.Phase.END) return;
        if (auth != null) auth.tick();
        StatsManager stats = StatsManager.get();
        if (stats != null) stats.tick();
    }

    // ───────── login ─────────

    /**
     * Highest priority so our Hello reaches the client before the packets other mods send on join (e.g. a
     * difficulty picker): the client then knows the login is pending and holds their screens back.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        AuthManager auth = auth();
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (auth != null) auth.onLogin(p);
        StatsManager stats = StatsManager.get();
        if (stats != null) stats.onLogin(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AuthManager auth = auth();
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        // Stats first: they need to know whether the player had logged in, which onLogout forgets.
        StatsManager stats = StatsManager.get();
        if (stats != null) stats.onLogout(p);
        if (auth != null) auth.onLogout(p);
    }

    // ───────── names (chat + tab) ─────────

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        AuthManager auth = auth();
        if (auth == null || !(event.getEntity() instanceof ServerPlayer p)) return;
        Component decorated = auth.decorateName(p, event.getDisplayname(), false);
        if (decorated != null) event.setDisplayname(decorated);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        AuthManager auth = auth();
        if (auth == null || !(event.getEntity() instanceof ServerPlayer p)) return;
        if (!AuthConfig.SHOW_IN_TAB.get() && !AuthConfig.AVATARS_IN_TAB.get()) return;
        Component base = event.getDisplayName() != null ? event.getDisplayName() : p.getName();
        Component decorated = auth.decorateName(p, base, true);
        if (decorated != null) event.setDisplayName(decorated);
    }

    // ───────── block everything until logged in ─────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChat(ServerChatEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCanceled(true);
            warn(event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        Entity source = event.getParseResults().getContext().getSource().getEntity();
        if (restricted(source)) {
            event.setCanceled(true);
            warn((Player) source);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (event.isCancelable() && restricted(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (restricted(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (restricted(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (restricted(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onToss(ItemTossEvent event) {
        if (restricted(event.getPlayer())) {
            // The item has already left the inventory when this fires: give it back.
            event.setCanceled(true);
            event.getPlayer().getInventory().placeItemBackInInventory(event.getEntity().getItem().copy());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPickup(EntityItemPickupEvent event) {
        if (restricted(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPickupXp(PlayerXpEvent.PickupXp event) {
        if (restricted(event.getEntity())) event.setCanceled(true);
    }

    /** Can't be hurt while logging in. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingAttackEvent event) {
        if (restricted(event.getEntity())) event.setCanceled(true);
    }

    /** Mobs ignore players who are logging in. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTarget(LivingChangeTargetEvent event) {
        if (restricted(event.getNewTarget())) event.setCanceled(true);
    }
}
