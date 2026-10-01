package com.npucraft.battleroyale.zone;
public final class ZoneDamage {
    private ZoneDamage() {}
    public record Context(java.util.UUID sessionId, int stageIndex, double outsideDistance, double amount) {
        public String origin() { return "ZONE"; }
    }
    public static double amount(Zone zone, double x, double z, ZoneProfile.Stage stage) {
        double distance=zone.distanceOutside(x,z);
        if (zone.contains(x,z)) return 0;
        double configured=Math.min(stage.maxDamagePerSecond(),
                stage.baseDamagePerSecond()+distance*stage.extraDamagePerBlock());
        // An empty final circle must never leave a permanently immune center, even with zero configured damage.
        return zone.halfSize()==0 ? Math.max(1,configured) : configured;
    }
    public static double healthAfter(double health, double maximum, double damage) {
        if (!Double.isFinite(health) || !Double.isFinite(maximum) || !Double.isFinite(damage) || maximum <= 0 || damage < 0)
            throw new IllegalArgumentException("Invalid health/damage");
        return Math.clamp(health-damage,0,maximum);
    }
}

