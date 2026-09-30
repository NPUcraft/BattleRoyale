package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.util.Checks;
import java.util.Objects;

/** Horizontal guidance using Bukkit yaw: zero faces south and positive yaw turns right. */
public final class ZoneNavigation {
    private static final String[] ARROWS={"↑","↗","→","↘","↓","↙","←","↖"};
    public record Point(double x,double z) {
        public Point { Checks.finite(x,"point x");Checks.finite(z,"point z"); }
    }
    public enum Target { ENTER_SAFE_ZONE, NEXT_CENTER, CURRENT_CENTER }
    public record Hint(Target target,double targetX,double targetZ,double distance,String arrow,boolean arrived) {
        public boolean outside() { return target==Target.ENTER_SAFE_ZONE; }
    }
    private ZoneNavigation() {}

    /** Aim at the destination center; measure to its square outside, and to its center inside. */
    public static Hint guide(Zone current,Zone next,double x,double z,double yaw) {
        Objects.requireNonNull(current);
        Checks.finite(x,"player x"); Checks.finite(z,"player z"); Checks.finite(yaw,"player yaw");
        Zone destination=next==null?current:next;
        double dx=destination.centerX()-x,dz=destination.centerZ()-z;
        boolean inside=destination.contains(x,z),arrived=dx==0&&dz==0;
        Target target=inside?(next==null?Target.CURRENT_CENTER:Target.NEXT_CENTER):Target.ENTER_SAFE_ZONE;
        double distance=inside?Math.hypot(dx,dz):destination.distanceOutside(x,z);
        return new Hint(target,destination.centerX(),destination.centerZ(),distance,arrow(dx,dz,yaw),arrived);
    }

    /** Eight compass arrows relative to the viewer, rather than absolute north. */
    public static String arrow(double dx,double dz,double yaw) {
        Checks.finite(dx,"direction x"); Checks.finite(dz,"direction z"); Checks.finite(yaw,"player yaw");
        if (dx==0 && dz==0) return "●";
        double targetYaw=Math.toDegrees(Math.atan2(-dx,dz));
        double relative=(targetYaw-yaw%360+720)%360;
        int sector=(int)Math.floor((relative+22.5)/45)%8;
        return ARROWS[sector];
    }
}
