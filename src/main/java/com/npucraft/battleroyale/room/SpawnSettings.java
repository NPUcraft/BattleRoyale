package com.npucraft.battleroyale.room;
public record SpawnSettings(double minDistance, int maxAttemptsPerPlayer) {
    public static final SpawnSettings DEFAULT = new SpawnSettings(64,200);
    public SpawnSettings {
        if (!Double.isFinite(minDistance) || minDistance < 0 || maxAttemptsPerPlayer < 1)
            throw new IllegalArgumentException("spawn min-distance >= 0 and max-attempts-per-player > 0 required");
    }
}

