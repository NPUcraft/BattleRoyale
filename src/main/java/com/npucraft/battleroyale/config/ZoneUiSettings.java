package com.npucraft.battleroyale.config;

public record ZoneUiSettings(boolean bossbarEnabled, int bossbarInterval, boolean wallEnabled, String particle,
        int wallInterval, double viewDistance, double spacing, double verticalSpacing,
        double below, double above, int maximumParticles,
        boolean navigationEnabled, int navigationInterval, boolean coloredWall, boolean worldBorderEnabled) {
    public static final ZoneUiSettings DEFAULT = new ZoneUiSettings(true,5,true,"END_ROD",5,64,2.5,1.5,3,6,300,true,5,true,true);

    /** Existing configuration call sites keep compiling; the per-player world border defaults on. */
    public ZoneUiSettings(boolean bossbarEnabled,int bossbarInterval,boolean wallEnabled,String particle,
            int wallInterval,double viewDistance,double spacing,double verticalSpacing,double below,double above,int maximumParticles,
            boolean navigationEnabled,int navigationInterval,boolean coloredWall) {
        this(bossbarEnabled,bossbarInterval,wallEnabled,particle,wallInterval,viewDistance,spacing,verticalSpacing,
                below,above,maximumParticles,navigationEnabled,navigationInterval,coloredWall,true);
    }

    /** Existing configurations gain navigation and colored edges without requiring a migration. */
    public ZoneUiSettings(boolean bossbarEnabled,int bossbarInterval,boolean wallEnabled,String particle,
            int wallInterval,double viewDistance,double spacing,double verticalSpacing,double below,double above,int maximumParticles) {
        this(bossbarEnabled,bossbarInterval,wallEnabled,particle,wallInterval,viewDistance,spacing,verticalSpacing,
                below,above,maximumParticles,true,5,true,true);
    }

    public ZoneUiSettings {
        if (bossbarInterval<1 || wallInterval<1 || navigationInterval<1 || navigationInterval>40
                || maximumParticles<1 || maximumParticles>1000
                || !bounded(viewDistance,1,256) || !bounded(spacing,.25,256)
                || !bounded(verticalSpacing,.25,64) || !bounded(below,0,64) || !bounded(above,0,64))
            throw new IllegalArgumentException("Invalid zone-ui bounds (interval >= 1, navigation interval 1..40, cap 1..1000, view 1..256, spacing >= .25, heights 0..64)");
        if (particle==null || particle.isBlank()) throw new IllegalArgumentException("zone-ui particle is required");
    }
    private static boolean bounded(double value,double min,double max) { return Double.isFinite(value) && value>=min && value<=max; }
}
