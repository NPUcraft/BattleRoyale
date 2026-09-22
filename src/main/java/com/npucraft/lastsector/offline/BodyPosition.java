package com.npucraft.lastsector.offline;
import java.util.UUID;
public record BodyPosition(UUID world,double x,double y,double z,float yaw,float pitch) {
    public BodyPosition {if(world==null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))throw new IllegalArgumentException("Invalid body position");}
}
