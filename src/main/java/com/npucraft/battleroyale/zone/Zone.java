package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.util.Checks;

/** Immutable axis-aligned square. Positive-size edges are included; a zero-size zone is empty. */
public record Zone(double centerX, double centerZ, double halfSize) {
    public Zone {
        Checks.finite(centerX, "centerX");
        Checks.finite(centerZ, "centerZ");
        Checks.finite(halfSize, "halfSize");
        if (halfSize < 0) throw new IllegalArgumentException("halfSize must be >= 0");
        Checks.finite(centerX - halfSize, "minX"); Checks.finite(centerX + halfSize, "maxX");
        Checks.finite(centerZ - halfSize, "minZ"); Checks.finite(centerZ + halfSize, "maxZ");
    }
    public double minX() { return centerX - halfSize; }
    public double maxX() { return centerX + halfSize; }
    public double minZ() { return centerZ - halfSize; }
    public double maxZ() { return centerZ + halfSize; }
    /** Tests a finite horizontal point; zero size has no safe center or included edges. */
    public boolean contains(double x, double z) {
        Checks.finite(x, "x"); Checks.finite(z, "z");
        return halfSize > 0 && x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }
    /** Euclidean distance to the square, or to the collapse point when empty. Zero distance alone does not imply safety. */
    public double distanceOutside(double x, double z) {
        Checks.finite(x, "x"); Checks.finite(z, "z");
        double dx = Math.max(0, Math.max(minX() - x, x - maxX()));
        double dz = Math.max(0, Math.max(minZ() - z, z - maxZ()));
        return Math.hypot(dx, dz);
    }
}
