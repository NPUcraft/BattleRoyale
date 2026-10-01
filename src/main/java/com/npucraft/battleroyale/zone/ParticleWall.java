package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.config.ZoneUiSettings;
import com.npucraft.battleroyale.util.Checks;
import java.util.*;

/** Locally clipped, nearest-first columns. Visible edges share one strict per-viewer budget. */
public final class ParticleWall {
    private ParticleWall() {}
    public record Point(double x, double y, double z) {}

    public static List<Point> sample(Zone zone,double x,double y,double z,ZoneUiSettings settings) {
        Objects.requireNonNull(zone); Objects.requireNonNull(settings);
        Checks.finite(x,"player x"); Checks.finite(y,"player y"); Checks.finite(z,"player z");
        if (zone.halfSize()==0) return List.of();
        int cap=settings.maximumParticles();
        List<Double> heights=axis(y-settings.below(),y+settings.above(),y+1.6,settings.verticalSpacing(),cap);
        List<Edge> edges=new ArrayList<>(4);
        edge(edges,true,zone.minX(),zone.minZ(),zone.maxZ(),x,z,heights,settings);
        edge(edges,true,zone.maxX(),zone.minZ(),zone.maxZ(),x,z,heights,settings);
        edge(edges,false,zone.minZ(),zone.minX(),zone.maxX(),x,z,heights,settings);
        edge(edges,false,zone.maxZ(),zone.minX(),zone.maxX(),x,z,heights,settings);
        // A very small budget still starts with the nearest visible edge.
        edges.sort(Comparator.comparingDouble(edge -> edge.distanceSquared(x,z)));
        Set<Point> points=new LinkedHashSet<>();
        int attempts=0;
        while (points.size()<cap && attempts<cap*4) {
            boolean remaining=false;
            for (Edge edge:edges) {
                Point point=edge.next();
                if (point==null) continue;
                remaining=true; attempts++; points.add(point);
                if (points.size()==cap || attempts==cap*4) break;
            }
            if (!remaining) break;
        }
        return List.copyOf(points);
    }

    private static void edge(List<Edge> out,boolean vertical,double fixed,double min,double max,
            double x,double z,List<Double> heights,ZoneUiSettings s) {
        double distance=Math.abs(fixed-(vertical?x:z));
        if (distance>s.viewDistance()) return;
        double reach=Math.sqrt(Math.max(0,s.viewDistance()*s.viewDistance()-distance*distance));
        double center=vertical?z:x,lo=Math.max(min,center-reach),hi=Math.min(max,center+reach);
        if (lo>hi) return;
        List<Double> along=axis(lo,hi,center,s.spacing(),s.maximumParticles());
        if (!along.isEmpty()) out.add(new Edge(vertical,fixed,along,heights));
    }

    /** Bounded independently of map size; never enumerates the full perimeter or full grid. */
    private static List<Double> axis(double lo,double hi,double origin,double spacing,int limit) {
        double center=Math.max(lo,Math.min(hi,origin));
        Set<Double> values=new LinkedHashSet<>(); values.add(center);
        for (int step=1;values.size()<limit && step<=limit;step++) {
            double positive=center+step*spacing,negative=center-step*spacing;
            if (positive>hi && negative<lo) break;
            if (positive<=hi) values.add(positive);
            if (values.size()<limit && negative>=lo) values.add(negative);
        }
        return List.copyOf(values);
    }

    private static final class Edge {
        private final boolean vertical;
        private final double fixed;
        private final List<Double> along,heights;
        private int column,height;
        Edge(boolean vertical,double fixed,List<Double> along,List<Double> heights) {
            this.vertical=vertical; this.fixed=fixed; this.along=along; this.heights=heights;
        }
        double distanceSquared(double x,double z) {
            double dx=(vertical?fixed:along.getFirst())-x,dz=(vertical?along.getFirst():fixed)-z;
            return dx*dx+dz*dz;
        }
        Point next() {
            if (column>=along.size()) return null;
            double position=along.get(column),y=heights.get(height);
            if (++height==heights.size()) { height=0; column++; }
            return new Point(vertical?fixed:position,y,vertical?position:fixed);
        }
    }
}
