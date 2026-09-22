package com.npucraft.lastsector.loot;

import com.npucraft.lastsector.zone.Zone;
import java.util.Optional;
import java.util.random.RandomGenerator;

public record LootArea(String id, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                       String table, double activationChance, int minSpawns, int maxSpawns, int maxAttempts) {
    public LootArea {
        if (minX > maxX || minY > maxY || minZ > maxZ || !Double.isFinite(activationChance)
                || activationChance < 0 || activationChance > 1 || minSpawns < 0 || maxSpawns < minSpawns
                || maxSpawns > 256 || maxAttempts < 1 || maxAttempts > 256)
            throw new IllegalArgumentException("Invalid loot area bounds/chance/spawns/attempts (limits 256)");
    }
    public record Bounds(int minX, int maxX, int minZ, int maxZ) {}
    /** Sample block centers only; their actual item coordinates must remain inside InitialZone. */
    public Optional<Bounds> intersection(Zone initial) {
        int x1 = Math.max(minX, (int)Math.ceil(initial.minX() - .5));
        int x2 = Math.min(maxX, (int)Math.floor(initial.maxX() - .5));
        int z1 = Math.max(minZ, (int)Math.ceil(initial.minZ() - .5));
        int z2 = Math.min(maxZ, (int)Math.floor(initial.maxZ() - .5));
        return x1 > x2 || z1 > z2 ? Optional.empty() : Optional.of(new Bounds(x1, x2, z1, z2));
    }
    public boolean activates(RandomGenerator random) { return activationChance == 1 || activationChance > 0 && random.nextDouble() < activationChance; }
    public int count(RandomGenerator random) { return random.nextInt(minSpawns, maxSpawns + 1); }
}
