package com.npucraft.lastsector.config;
public record ZoneUiSettings(boolean bossbarEnabled, int bossbarInterval, boolean wallEnabled, String particle,
        int wallInterval, double viewDistance, double spacing, double verticalSpacing,
        double below, double above, int maximumParticles) {
    public static final ZoneUiSettings DEFAULT = new ZoneUiSettings(true,5,true,"END_ROD",5,64,2.5,1.5,3,6,300);
    public ZoneUiSettings {
        if (bossbarInterval<1 || wallInterval<1 || maximumParticles<1 || maximumParticles>1000
                || !bounded(viewDistance,1,256) || !bounded(spacing,.25,256)
                || !bounded(verticalSpacing,.25,64) || !bounded(below,0,64) || !bounded(above,0,64))
            throw new IllegalArgumentException("Invalid zone-ui bounds (interval >= 1, cap 1..1000, view 1..256, spacing >= .25, heights 0..64)");
    }
    private static boolean bounded(double value,double min,double max) { return Double.isFinite(value) && value>=min && value<=max; }
}

