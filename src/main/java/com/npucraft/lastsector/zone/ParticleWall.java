package com.npucraft.lastsector.zone;
import com.npucraft.lastsector.config.ZoneUiSettings;
import java.util.*;
/** Clips four edges to a local disk before sampling, never iterates a whole perimeter. */
public final class ParticleWall {
    private ParticleWall() {}
    public record Point(double x, double y, double z) {}
    public static List<Point> sample(Zone zone,double x,double y,double z,ZoneUiSettings settings) {
        List<Point> points=new ArrayList<>();
        edge(points,true,zone.minX(),zone.minZ(),zone.maxZ(),x,y,z,settings);
        edge(points,true,zone.maxX(),zone.minZ(),zone.maxZ(),x,y,z,settings);
        edge(points,false,zone.minZ(),zone.minX(),zone.maxX(),x,y,z,settings);
        edge(points,false,zone.maxZ(),zone.minX(),zone.maxX(),x,y,z,settings);
        return List.copyOf(points);
    }
    private static void edge(List<Point> out,boolean vertical,double fixed,double min,double max,
            double x,double y,double z,ZoneUiSettings s) {
        double distance=Math.abs(fixed-(vertical?x:z));
        if (distance>s.viewDistance()) return;
        double reach=Math.sqrt(s.viewDistance()*s.viewDistance()-distance*distance);
        double center=vertical?z:x, lo=Math.max(min,center-reach), hi=Math.min(max,center+reach);
        for (double along=lo; along<=hi && out.size()<s.maximumParticles(); along+=s.spacing())
            for (double height=y-s.below(); height<=y+s.above() && out.size()<s.maximumParticles(); height+=s.verticalSpacing())
                out.add(new Point(vertical?fixed:along,height,vertical?along:fixed));
    }
}

