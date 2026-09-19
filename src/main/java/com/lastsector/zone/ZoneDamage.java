package com.lastsector.zone;
public final class ZoneDamage {
    private ZoneDamage() {}
    public record Context(java.util.UUID sessionId, int stageIndex, double outsideDistance, double amount) {
        public String origin() { return "ZONE"; }
    }
    public static double amount(Zone zone, double x, double z, ZoneProfile.Stage stage) {
        double distance=zone.distanceOutside(x,z);
        return distance == 0 ? 0 : Math.min(stage.maxDamagePerSecond(),
                stage.baseDamagePerSecond()+distance*stage.extraDamagePerBlock());
    }
    public static double healthAfter(double health, double maximum, double damage) {
        if (!Double.isFinite(health) || !Double.isFinite(maximum) || !Double.isFinite(damage) || maximum <= 0 || damage < 0)
            throw new IllegalArgumentException("Invalid health/damage");
        return Math.clamp(health-damage,0,maximum);
    }
}

