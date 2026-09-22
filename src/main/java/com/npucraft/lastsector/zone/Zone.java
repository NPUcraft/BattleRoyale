package com.npucraft.lastsector.zone;

import com.npucraft.lastsector.util.Checks;

/** Immutable axis-aligned square. Edges are included; outside distance is Euclidean. */
public record Zone(double centerX, double centerZ, double halfSize) {
    public Zone {
        Checks.finite(centerX, "centerX");
        Checks.finite(centerZ, "centerZ");
        Checks.finite(halfSize, "halfSize");
        if (halfSize <= 0) throw new IllegalArgumentException("halfSize must be > 0");
        Checks.finite(centerX - halfSize, "minX"); Checks.finite(centerX + halfSize, "maxX");
        Checks.finite(centerZ - halfSize, "minZ"); Checks.finite(centerZ + halfSize, "maxZ");
    }
    public double minX() { return centerX - halfSize; }
    public double maxX() { return centerX + halfSize; }
    public double minZ() { return centerZ - halfSize; }
    public double maxZ() { return centerZ + halfSize; }
    /** Tests a finite horizontal point; all four edges and corners are inside. */
    public boolean contains(double x, double z) {
        Checks.finite(x, "x"); Checks.finite(z, "z");
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }
    /** Returns zero inside or on an edge, otherwise Euclidean distance to the nearest boundary point. */
    public double distanceOutside(double x, double z) {
        Checks.finite(x, "x"); Checks.finite(z, "z");
        double dx = Math.max(0, Math.max(minX() - x, x - maxX()));
        double dz = Math.max(0, Math.max(minZ() - z, z - maxZ()));
        return Math.hypot(dx, dz);
    }
}
