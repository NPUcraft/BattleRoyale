package com.npucraft.battleroyale.room;

import com.npucraft.battleroyale.util.Checks;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Permanent room configuration, never the mutable state of a match. */
public record RoomDefinition(String id, String displayName, int minPlayers, int maxPlayers,
        int teamSize, Duration pvpProtectionDuration, List<String> mapPool,
        String loadoutId, String zoneProfileId, boolean allowExternalSpectators, Duration countdownDuration, SpawnSettings spawn) {
    public RoomDefinition(String id, String displayName, int minPlayers, int maxPlayers,
            int teamSize, Duration protection, List<String> maps, String loadout, String zone, boolean spectators, Duration countdown) {
        this(id, displayName, minPlayers, maxPlayers, teamSize, protection, maps, loadout, zone, spectators, countdown, SpawnSettings.DEFAULT);
    }
    /** M1 source compatibility; omitted countdown defaults to thirty seconds. */
    public RoomDefinition(String id, String displayName, int minPlayers, int maxPlayers,
            int teamSize, Duration protection, List<String> maps, String loadout, String zone, boolean spectators) {
        this(id, displayName, minPlayers, maxPlayers, teamSize, protection, maps, loadout, zone, spectators, Duration.ofSeconds(30));
    }
    public RoomDefinition {
        Objects.requireNonNull(spawn);
        Checks.text(id, "id"); Checks.text(displayName, "displayName");
        if (minPlayers < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
        if (maxPlayers < minPlayers) throw new IllegalArgumentException("maxPlayers must be >= minPlayers (" + minPlayers + ")");
        if (teamSize < 1) throw new IllegalArgumentException("teamSize must be >= 1");
        Objects.requireNonNull(pvpProtectionDuration, "pvpProtectionDuration");
        if (pvpProtectionDuration.isNegative()) throw new IllegalArgumentException("pvpProtectionDuration must be >= 0");
        mapPool = List.copyOf(mapPool);
        if (mapPool.isEmpty()) throw new IllegalArgumentException("mapPool must not be empty");
        mapPool.forEach(idValue -> Checks.text(idValue, "mapPool entry"));
        Checks.text(loadoutId, "loadoutId"); Checks.text(zoneProfileId, "zoneProfileId");
        Objects.requireNonNull(countdownDuration, "countdownDuration");
        if (countdownDuration.isNegative() || countdownDuration.isZero() || countdownDuration.getNano() != 0
                || countdownDuration.getSeconds() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("countdownDuration must be whole seconds in [1, 2147483647]");
    }
}
