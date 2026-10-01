package com.npucraft.battleroyale.flight;

import java.util.Collection;

/** Standing-player contact, independent of client on-ground flags and block opacity/full-cube tests. */
public final class PlayerLandingGeometry {
    public static final double RADIUS=.3, HEIGHT=1.8, CONTACT_TOLERANCE=1.0/32;
    private static final double EPSILON=1e-7;
    private PlayerLandingGeometry() {}
    /** World-space collision component; stairs intentionally supply several boxes. */
    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public boolean horizontal(double x,double z) {
            return maxX>x-RADIUS+EPSILON&&minX<x+RADIUS-EPSILON
                    &&maxZ>z-RADIUS+EPSILON&&minZ<z+RADIUS-EPSILON;
        }
        public boolean touchesBody(double x,double y,double z) {
            return horizontal(x,z)&&maxY>y+EPSILON&&minY<y+HEIGHT-EPSILON;
        }
        public boolean touchesFeetOrBody(double x,double y,double z) {
            return horizontal(x,z)&&maxY>=y-CONTACT_TOLERANCE&&minY<y+HEIGHT-EPSILON;
        }
    }
    public static boolean standing(double x,double y,double z,Collection<Box> collisions) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return false;
        boolean supported=false;
        for(Box box:collisions) {
            if(box.touchesBody(x,y,z))return false;
            if(box.horizontal(x,z)&&y>=box.maxY()-EPSILON&&y-box.maxY()<=CONTACT_TOLERANCE)supported=true;
        }
        return supported;
    }
}
