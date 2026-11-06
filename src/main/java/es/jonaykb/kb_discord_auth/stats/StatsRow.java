package es.jonaykb.kb_discord_auth.stats;

import java.util.UUID;

/** One player in the in-game rankings. */
public record StatsRow(UUID uuid, String name, PlayerStats stats) {}
