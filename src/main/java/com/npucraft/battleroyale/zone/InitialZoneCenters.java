package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.map.PlayableArea;
import com.npucraft.battleroyale.util.Checks;
import java.util.List;
import java.util.random.RandomGenerator;

/** Map-specific opening center candidates. Jitter is uniform over a disk, not over its radius. */
public record InitialZoneCenters(double jitterRadius, List<Point> points) {
    public InitialZoneCenters {
        Checks.finite(jitterRadius, "jitterRadius");
        if (jitterRadius < 0) throw new IllegalArgumentException("jitterRadius >= 0 required");
        points = List.copyOf(points);
        if (points.isEmpty() || points.size() > 256) throw new IllegalArgumentException("1 to 256 initial center points required");
    }
    public void validate(PlayableArea area, double halfSize) {
        Checks.finite(halfSize, "halfSize");
        double extent = halfSize + jitterRadius;
        if (halfSize <= 0 || !Double.isFinite(extent)) throw new IllegalArgumentException("Invalid initial center extent");
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (!Double.isFinite(point.x() - extent) || !Double.isFinite(point.x() + extent)
                    || !Double.isFinite(point.z() - extent) || !Double.isFinite(point.z() + extent)
                    || !area.contains(point.x() - extent, point.z() - extent)
                    || !area.contains(point.x() + extent, point.z() + extent))
                throw new IllegalArgumentException("initial-centers.points[" + i + "] at (" + point.x() + ", " + point.z()
                        + ") plus jitter-radius=" + jitterRadius + " and half-size=" + halfSize + " must fit playable area " + area);
        }
    }
    public Zone choose(PlayableArea area, double halfSize, RandomGenerator random) {
        validate(area, halfSize);
        Point point = points.get(random.nextInt(points.size()));
        double x = point.x(), z = point.z();
        if (jitterRadius > 0) {
            double radius = Math.sqrt(random.nextDouble()) * jitterRadius;
            double angle = random.nextDouble() * 2 * Math.PI;
            x += radius * Math.cos(angle); z += radius * Math.sin(angle);
        }
        var zone = new Zone(x, z, halfSize);
        if (!area.contains(zone)) throw new IllegalArgumentException("Initial center rounding exceeded playable area");
        return zone;
    }
    public record Point(double x, double z) {
        public Point { Checks.finite(x, "x"); Checks.finite(z, "z"); }
    }
}
