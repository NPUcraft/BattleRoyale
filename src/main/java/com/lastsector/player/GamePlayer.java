package com.lastsector.player;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
/** Immutable player snapshot. An empty teamId represents an unassigned player. */
public record GamePlayer(UUID playerId, PlayerState state, Optional<UUID> teamId, int kills, int assists) {
    public GamePlayer {
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(state, "state");
        Objects.requireNonNull(teamId, "teamId");
        if (kills < 0 || assists < 0) throw new IllegalArgumentException("kills and assists must be >= 0");
    }
}

