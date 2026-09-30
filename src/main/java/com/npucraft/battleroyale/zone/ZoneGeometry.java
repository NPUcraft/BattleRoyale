package com.npucraft.battleroyale.zone;
import com.npucraft.battleroyale.map.PlayableArea;
import java.util.random.RandomGenerator;
public final class ZoneGeometry {
    private ZoneGeometry() {}
    public static Zone initial(PlayableArea area, double halfSize, RandomGenerator random) {
        if (area.maxX() - area.minX() < 2 * halfSize || area.maxZ() - area.minZ() < 2 * halfSize)
            throw new IllegalArgumentException("Playable area cannot contain initial halfSize=" + halfSize);
        return new Zone(sample(area.minX()+halfSize, area.maxX()-halfSize, random),
                sample(area.minZ()+halfSize, area.maxZ()-halfSize, random), halfSize);
    }
    public static Zone next(Zone current, double halfSize, RandomGenerator random) {
        if (!(halfSize > 0 && halfSize < current.halfSize())) throw new IllegalArgumentException("Target must shrink");
        double inset = current.halfSize()-halfSize;
        return new Zone(sample(current.centerX()-inset, current.centerX()+inset, random),
                sample(current.centerZ()-inset, current.centerZ()+inset, random), halfSize);
    }
    private static double sample(double min, double max, RandomGenerator random) {
        return min == max ? min : min + random.nextDouble() * (max-min);
    }
    public static Zone interpolate(Zone from, Zone to, double progress) {
        if (!Double.isFinite(progress)) throw new IllegalArgumentException("Nonfinite progress");
        if (progress <= 0) return from;
        if (progress >= 1) return to;
        return new Zone(from.centerX()+(to.centerX()-from.centerX())*progress,
                from.centerZ()+(to.centerZ()-from.centerZ())*progress,
                from.halfSize()+(to.halfSize()-from.halfSize())*progress);
    }
}

