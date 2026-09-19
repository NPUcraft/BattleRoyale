package com.lastsector.team;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
/** Immutable membership snapshot; solo uses the same model as any other team size. */
public record GameTeam(UUID teamId, Set<UUID> playerIds) {
    public GameTeam { Objects.requireNonNull(teamId, "teamId"); playerIds = Set.copyOf(playerIds); }
}

