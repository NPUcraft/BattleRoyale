package com.npucraft.battleroyale.map;

import com.npucraft.battleroyale.zone.Zone;
import com.npucraft.battleroyale.util.Checks;
import java.util.Objects;

/** Immutable rectangular bounds in the horizontal plane, inclusive of edges. */
public record PlayableArea(double minX, double maxX, double minZ, double maxZ) {
    public PlayableArea {
        Checks.finite(minX, "minX"); Checks.finite(maxX, "maxX");
        Checks.finite(minZ, "minZ"); Checks.finite(maxZ, "maxZ");
        if (minX >= maxX || minZ >= maxZ) throw new IllegalArgumentException("min bounds must be < max bounds");
    }
    /** Tests a finite point against inclusive rectangular bounds. */
    public boolean contains(double x, double z) {
        Checks.finite(x, "x"); Checks.finite(z, "z");
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
    /** Tests that the entire square, not only its center, is inside these bounds. */
    public boolean contains(Zone zone) {
        Objects.requireNonNull(zone, "zone");
        return contains(zone.minX(), zone.minZ()) && contains(zone.maxX(), zone.maxZ());
    }
}
