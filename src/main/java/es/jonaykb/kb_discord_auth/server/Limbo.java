package es.jonaykb.kb_discord_auth.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import es.jonaykb.kb_discord_auth.KbDiscordAuth;
import es.jonaykb.kb_discord_auth.config.AuthConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * The waiting room. A player who hasn't logged in with Discord yet waits in an empty dimension
 * ({@code kb_discord_auth:limbo}) with the inventory put aside, so someone using another player's name can't see
 * where that player is, their base or what they carry. Packets from other mods wait until the login is done.
 *
 * <ul>
 * <li>The move happens when the player's data is loaded, before Minecraft tells the client where it is: the real
 *     position never reaches the client until the login is done.</li>
 * <li>A player in the waiting room is never saved, so if they leave or the server crashes, their file keeps their
 *     real position and inventory.</li>
 * </ul>
 */
public final class Limbo {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(KbDiscordAuth.MOD_ID, "limbo"));
    private static final double SPOT_Y = 128;
    /** Players wait this far apart, out of each other's tracking range. */
    private static final int SPOT_SPACING = 1024;
    private static final int MAX_HELD_PACKETS = 1024;

    /** Where a player really is and what they carry, while they wait. */
    static final class Stash {
        final ResourceKey<Level> dimension;
        final double x, y, z;
        final float yRot, xRot;
        final ListTag inventory;
        @Nullable final CompoundTag rootVehicle;
        /** The player's spot in the waiting room. */
        final double spotX, spotZ;

        Stash(ResourceKey<Level> dimension, ServerPlayer p, @Nullable CompoundTag rootVehicle, int slot) {
            this.dimension = dimension;
            x = p.getX();
            y = p.getY();
            z = p.getZ();
            yRot = p.getYRot();
            xRot = p.getXRot();
            inventory = p.getInventory().save(new ListTag());
            this.rootVehicle = rootVehicle;
            spotX = slot * (double) SPOT_SPACING + 0.5;
            spotZ = 0.5;
        }
    }

    /** Packets from other mods held until the login is done. */
    private static final class Held {
        private final List<ServerboundCustomPayloadPacket> packets = new ArrayList<>();
        private boolean open;
        private boolean warned;

        /** False if the login is already done and the packet should be handled now. */
        synchronized boolean offer(ServerboundCustomPayloadPacket packet, ServerPlayer player) {
            if (open) return false;
            if (packets.size() < MAX_HELD_PACKETS) {
                packets.add(new ServerboundCustomPayloadPacket(packet.getIdentifier(),
                        new FriendlyByteBuf(Unpooled.copiedBuffer(packet.getInternalData().slice()))));
            } else if (!warned) {
                warned = true;
                LOGGER.warn("Dropping packets from {}: more than {} sent before the Discord login",
                        player.getGameProfile().getName(), MAX_HELD_PACKETS);
            }
            return true;
        }

        synchronized List<ServerboundCustomPayloadPacket> open() {
            open = true;
            List<ServerboundCustomPayloadPacket> out = new ArrayList<>(packets);
            packets.clear();
            return out;
        }
    }

    /** Players in the waiting room, who must not be saved. Server thread only. */
    private static final Set<ServerPlayer> unsaved = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Stashes made on load, picked up by the login a moment later. Server thread only. */
    private static final Map<ServerPlayer, Stash> pending = new IdentityHashMap<>();
    /** Any thread: packets arrive on the network threads. */
    private static final Map<ServerPlayer, Held> held = new ConcurrentHashMap<>();
    private static int nextSlot;

    private Limbo() {}

    private static boolean applies(ServerPlayer player) {
        AuthManager auth = AuthManager.get();
        return auth != null && auth.active() && AuthConfig.LIMBO.get() && !AuthManager.isLoadTestBot(player);
    }

    // ───────────────────────────── join ─────────────────────────────

    /**
     * Called by the PlayerList mixin right after the player's data is loaded, before they are placed in the world.
     * Puts their real position and inventory aside, empties the inventory and returns the tag Minecraft will read
     * the dimension from, now pointing at the waiting room.
     */
    @Nullable
    public static CompoundTag onLoad(MinecraftServer server, ServerPlayer player, @Nullable CompoundTag tag) {
        if (!applies(player)) return tag;
        if (server.getLevel(LEVEL) == null) {
            LOGGER.error("The waiting room dimension {} is missing: players will wait where they are", LEVEL.location());
            return tag;
        }
        ResourceKey<Level> dimension = Level.OVERWORLD;
        if (tag != null && tag.contains("Dimension")) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("Dimension"));
            if (id != null && server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) != null) {
                dimension = ResourceKey.create(Registries.DIMENSION, id);
            }
        }
        CompoundTag out = tag == null ? new CompoundTag() : tag.copy();
        CompoundTag vehicle = out.contains("RootVehicle", 10) ? out.getCompound("RootVehicle") : null;
        out.remove("RootVehicle"); // brought back on login, in the real world
        out.putString("Dimension", LEVEL.location().toString());

        Stash stash = new Stash(dimension, player, vehicle, nextSlot);
        nextSlot = (nextSlot + 1) % 4096;
        player.getInventory().clearContent();
        player.setPos(stash.spotX, SPOT_Y, stash.spotZ);
        pending.put(player, stash);
        unsaved.add(player);
        return out;
    }

    /** The stash made when this player's data was loaded, or null if they don't wait in the waiting room. */
    @Nullable
    static Stash take(ServerPlayer player) {
        return pending.remove(player);
    }

    // ───────────────────────────── waiting ─────────────────────────────

    /** Keeps the player on their spot (they can still look around). */
    static void hold(MinecraftServer server, ServerPlayer p, Stash s) {
        ServerLevel limbo = server.getLevel(LEVEL);
        if (limbo == null) return;
        // Something to stand on: without it vanilla kicks them for flying. Barrier blocks are invisible.
        BlockPos floor = BlockPos.containing(s.spotX, SPOT_Y - 1, s.spotZ);
        if (!limbo.getBlockState(floor).is(Blocks.BARRIER)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) limbo.setBlockAndUpdate(floor.offset(dx, 0, dz), Blocks.BARRIER.defaultBlockState());
            }
        }
        if (p.level() != limbo) {
            p.teleportTo(limbo, s.spotX, SPOT_Y, s.spotZ, p.getYRot(), p.getXRot());
        } else if (p.distanceToSqr(s.spotX, SPOT_Y, s.spotZ) > 0.01) {
            p.connection.teleport(s.spotX, SPOT_Y, s.spotZ, p.getYRot(), p.getXRot());
        }
    }

    // ───────────────────────────── login done ─────────────────────────────

    /** Brings the player back where they were, with their inventory, and handles the packets that were held. */
    static void leave(MinecraftServer server, ServerPlayer p, @Nullable Stash s) {
        if (s != null) {
            ServerLevel level = server.getLevel(s.dimension);
            if (level == null) level = server.overworld();
            p.teleportTo(level, s.x, s.y, s.z, s.yRot, s.xRot);
            restoreInventory(p, s.inventory);
            if (s.rootVehicle != null) remount(level, p, s.rootVehicle);
            unsaved.remove(p);
        }
        Held h = held.get(p);
        if (h != null) {
            for (ServerboundCustomPayloadPacket packet : h.open()) p.connection.handleCustomPayload(packet);
        } else {
            held.computeIfAbsent(p, k -> new Held()).open();
        }
    }

    /** Items other mods gave while waiting (e.g. a starter kit) are kept on top of the real inventory. */
    private static void restoreInventory(ServerPlayer p, ListTag saved) {
        List<ItemStack> extra = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (!stack.isEmpty()) extra.add(stack.copy());
        }
        p.getInventory().load(saved);
        for (ItemStack stack : extra) p.getInventory().placeItemBackInInventory(stack);
        p.inventoryMenu.broadcastChanges();
    }

    /** Same as vanilla does on join: load the vehicle the player logged out on and sit them on it again. */
    private static void remount(ServerLevel level, ServerPlayer p, CompoundTag rootVehicle) {
        Entity vehicle = EntityType.loadEntityRecursive(rootVehicle.getCompound("Entity"), level,
                e -> level.addWithUUID(e) ? e : null);
        if (vehicle == null) return;
        UUID attach = rootVehicle.hasUUID("Attach") ? rootVehicle.getUUID("Attach") : null;
        if (vehicle.getUUID().equals(attach)) {
            p.startRiding(vehicle, true);
        } else {
            for (Entity e : vehicle.getIndirectPassengers()) {
                if (e.getUUID().equals(attach)) {
                    p.startRiding(e, true);
                    break;
                }
            }
        }
        if (!p.isPassenger()) {
            LOGGER.warn("Couldn't put {} back on their vehicle", p.getGameProfile().getName());
            vehicle.discard();
            for (Entity e : vehicle.getIndirectPassengers()) e.discard();
        }
    }

    // ───────────────────────────── hooks ─────────────────────────────

    /** PlayerList mixin: a player in the waiting room is never saved, so their file keeps the real state. */
    public static boolean skipSave(ServerPlayer player) {
        return unsaved.contains(player);
    }

    /** PlayerList mixin, after the player has left and been (or not) saved. */
    public static void forget(ServerPlayer player) {
        unsaved.remove(player);
        pending.remove(player);
        held.remove(player);
    }

    /**
     * Network threads: true if a packet from another mod must wait until the login is done (it is kept and handled
     * then). Vanilla, Forge and this mod's own packets always go through.
     */
    public static boolean holdPacket(ServerPlayer player, ServerboundCustomPayloadPacket packet) {
        String ns = packet.getIdentifier().getNamespace();
        if (ns.equals("minecraft") || ns.equals("forge") || ns.equals("fml") || ns.equals(KbDiscordAuth.MOD_ID)) {
            return false;
        }
        if (!applies(player)) return false;
        if (player.hasDisconnected()) return true;
        return held.computeIfAbsent(player, k -> new Held()).offer(packet, player);
    }
}
