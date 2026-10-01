package com.npucraft.battleroyale.loot;

import java.util.List;
import java.util.random.RandomGenerator;

/** Rejection-sampled airdrop landing sites: keeps rounds apart but always returns an in-bounds point. */
public final class AirdropPlacement {
    public record Point(int x,int z) {}
    private AirdropPlacement() {}
    /** Uniform rejection sampling with one relaxed retry; never loops forever and never returns null. */
    public static Point choose(int minX,int maxX,int minZ,int maxZ,double minDistance,List<Point> used,RandomGenerator random,int attempts) {
        if(minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Empty airdrop bounds");
        if(attempts<1)throw new IllegalArgumentException("Airdrop spacing requires at least one attempt");
        double threshold=Math.max(0,minDistance);
        Point fallback=sample(minX,maxX,minZ,maxZ,random);
        if(apart(fallback,used,threshold))return fallback;
        for(int i=1;i<attempts;i++){
            Point candidate=sample(minX,maxX,minZ,maxZ,random);
            if(apart(candidate,used,threshold))return candidate;
        }
        // Graceful degradation: halve the required separation before giving up on spacing entirely.
        double relaxed=threshold*.5;
        if(relaxed>0)for(int i=0;i<attempts;i++){
            Point candidate=sample(minX,maxX,minZ,maxZ,random);
            if(apart(candidate,used,relaxed))return candidate;
        }
        return fallback;
    }
    private static Point sample(int minX,int maxX,int minZ,int maxZ,RandomGenerator random){
        return new Point(random.nextInt(minX,maxX+1),random.nextInt(minZ,maxZ+1));
    }
    private static boolean apart(Point candidate,List<Point> used,double minDistance){
        double squared=minDistance*minDistance;
        for(Point point:used){double dx=candidate.x()-point.x(),dz=candidate.z()-point.z();if(dx*dx+dz*dz<squared)return false;}
        return true;
    }
}
