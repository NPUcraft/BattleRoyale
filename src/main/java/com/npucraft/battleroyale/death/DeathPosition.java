package com.npucraft.battleroyale.death;
import java.util.UUID;
public record DeathPosition(UUID world,double x,double y,double z) {
    public DeathPosition { if(world==null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Invalid death position"); }
    public DeathPosition visible(int minHeight,int maxHeight) { return new DeathPosition(world,x,Math.max(minHeight+2,Math.min(maxHeight-3,y)),z); }
    public double distanceSquared(DeathPosition other) {
        if(!world.equals(other.world)) return Double.POSITIVE_INFINITY;
        return (x-other.x)*(x-other.x)+(y-other.y)*(y-other.y)+(z-other.z)*(z-other.z);
    }
}
