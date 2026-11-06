package es.jonaykb.kb_discord_auth.stats;

import java.util.Comparator;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** The stats that have a ranking, in the order they are shown. Used on both sides. */
public enum StatKind {
    PLAY_TIME("play_time", PlayerStats::playTimeSeconds, PlayerStats::playTime),
    DEATHS("deaths", PlayerStats::deaths, s -> Integer.toString(s.deaths())),
    MOB_KILLS("mob_kills", PlayerStats::mobKills, s -> Integer.toString(s.mobKills())),
    DISTANCE("distance", PlayerStats::distanceCm, PlayerStats::distance),
    ADVANCEMENTS("advancements", PlayerStats::advancements, s -> Integer.toString(s.advancements()));

    private final String id;
    private final ToLongFunction<PlayerStats> value;
    private final Function<PlayerStats, String> format;

    StatKind(String id, ToLongFunction<PlayerStats> value, Function<PlayerStats, String> format) {
        this.id = id;
        this.value = value;
        this.format = format;
    }

    public String translationKey() {
        return "kb_discord_auth.stats." + id;
    }

    public long value(PlayerStats s) {
        return value.applyAsLong(s);
    }

    public String format(PlayerStats s) {
        return format.apply(s);
    }

    /** Highest first; ties by name so the order is the same on the server and the client. */
    public <T> Comparator<T> order(Function<T, PlayerStats> stats, Function<T, String> name) {
        return Comparator.<T>comparingLong(t -> -value(stats.apply(t)))
                .thenComparing(t -> name.apply(t), String.CASE_INSENSITIVE_ORDER);
    }
}
