package es.jonaykb.kb_discord_auth.stats;

import java.util.Locale;
import java.util.Set;

import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;

/** The stats sent to the bot and shown in the tab list. All are cumulative totals. */
public record PlayerStats(long playTimeSeconds, int deaths, int mobKills, long distanceCm, int advancements) {

    /**
     * Ways of moving that count as "distance travelled". Creative/spectator flight and falling are left out
     * on purpose: they would let anyone top the leaderboard without playing.
     */
    private static final ResourceLocation[] DISTANCE = {
            Stats.WALK_ONE_CM, Stats.CROUCH_ONE_CM, Stats.SPRINT_ONE_CM, Stats.WALK_ON_WATER_ONE_CM,
            Stats.WALK_UNDER_WATER_ONE_CM, Stats.SWIM_ONE_CM, Stats.CLIMB_ONE_CM, Stats.AVIATE_ONE_CM,
            Stats.BOAT_ONE_CM, Stats.HORSE_ONE_CM, Stats.MINECART_ONE_CM, Stats.PIG_ONE_CM, Stats.STRIDER_ONE_CM,
    };

    public static PlayerStats of(StatsCounter stats, int advancements) {
        long distance = 0;
        for (ResourceLocation id : DISTANCE) distance += value(stats, id);
        return new PlayerStats(
                value(stats, Stats.PLAY_TIME) / 20L,
                (int) value(stats, Stats.DEATHS),
                (int) value(stats, Stats.MOB_KILLS),
                distance,
                advancements);
    }

    /** Live stats of an online player. Server thread only. */
    public static PlayerStats of(ServerPlayer player) {
        return of(player.getStats(), countAdvancements(player));
    }

    private static long value(StatsCounter stats, ResourceLocation id) {
        return Math.max(0, stats.getValue(Stats.CUSTOM.get(id)));
    }

    /** Completed advancements that show up in the advancements screen (recipes and hidden roots don't count). */
    static int countAdvancements(ServerPlayer player) {
        int n = 0;
        for (Advancement a : player.server.getAdvancements().getAllAdvancements()) {
            if (a.getDisplay() != null && player.getAdvancements().getOrStartProgress(a).isDone()) n++;
        }
        return n;
    }

    /** Ids of the advancements that count, to read offline players' files away from the server thread. */
    static Set<String> countedAdvancementIds(MinecraftServer server) {
        return server.getAdvancements().getAllAdvancements().stream()
                .filter(a -> a.getDisplay() != null)
                .map(a -> a.getId().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ───────── formatting for the tab list ─────────

    public String playTime() {
        long minutes = playTimeSeconds / 60;
        long hours = minutes / 60;
        if (hours >= 24) return hours / 24 + "d " + hours % 24 + "h";
        if (hours > 0) return hours + "h " + minutes % 60 + "m";
        return minutes + "m";
    }

    public String distance() {
        double meters = distanceCm / 100.0;
        if (meters < 1000) return (long) meters + " m";
        return String.format(Locale.ROOT, "%.1f km", meters / 1000.0);
    }
}
