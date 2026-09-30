package com.npucraft.battleroyale.progression;

import java.util.Objects;
import java.util.UUID;

/** Actual persisted lifetime statistics for one displayed player. */
public record LobbyRankingRow(UUID player,String name,int rating,long kills,long wins) {
    public LobbyRankingRow {Objects.requireNonNull(player);Objects.requireNonNull(name);if(kills<0||wins<0)throw new IllegalArgumentException("Invalid lifetime statistics");}
}
