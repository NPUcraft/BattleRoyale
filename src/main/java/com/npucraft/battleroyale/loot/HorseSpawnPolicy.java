package com.npucraft.battleroyale.loot;

import java.util.*;

/** Permanent, bounded claims prevent killing a horse or taking its saddle from replenishing it. */
public final class HorseSpawnPolicy {
    public record Position(double x,double z){}
    private final HorseSettings settings;
    private final List<Position> claimed=new ArrayList<>();
    public HorseSpawnPolicy(HorseSettings settings,double[] saved){
        this.settings=Objects.requireNonNull(settings);
        if(saved==null||(saved.length&1)!=0||saved.length>HorseSettings.MAX_PER_SESSION*2)throw new IllegalArgumentException("Invalid horse spawn ledger");
        for(int i=0;i<saved.length;i+=2){
            if(!Double.isFinite(saved[i])||!Double.isFinite(saved[i+1])||Math.abs(saved[i])>30_000_000||Math.abs(saved[i+1])>30_000_000)
                throw new IllegalArgumentException("Invalid horse spawn position");
            claimed.add(new Position(saved[i],saved[i+1]));
        }
    }
    public boolean available(){return settings.enabled()&&claimed.size()<settings.maxPerSession();}
    public int count(){return claimed.size();}
    public boolean separated(double x,double z){return claimed.stream().allMatch(at->Math.hypot(at.x()-x,at.z()-z)>=settings.minDistance());}
    public boolean claim(double x,double z){
        if(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000)throw new IllegalArgumentException("Invalid horse spawn position");
        if(!available()||!separated(x,z))return false;claimed.add(new Position(x,z));return true;
    }
    public double[] snapshot(){double[] result=new double[claimed.size()*2];for(int i=0;i<claimed.size();i++){result[i*2]=claimed.get(i).x();result[i*2+1]=claimed.get(i).z();}return result;}
    /** Bukkit supports LONG_ARRAY, not DOUBLE_ARRAY. Preserve each coordinate's IEEE-754 bits. */
    public long[] encodedSnapshot(){double[] coordinates=snapshot();long[] result=new long[coordinates.length];for(int i=0;i<coordinates.length;i++)result[i]=Double.doubleToLongBits(coordinates[i]);return result;}
    public static HorseSpawnPolicy fromEncoded(HorseSettings settings,long[] saved){
        if(saved==null||(saved.length&1)!=0||saved.length>HorseSettings.MAX_PER_SESSION*2)throw new IllegalArgumentException("Invalid encoded horse spawn ledger");
        double[] coordinates=new double[saved.length];for(int i=0;i<saved.length;i++)coordinates[i]=Double.longBitsToDouble(saved[i]);
        return new HorseSpawnPolicy(settings,coordinates);
    }
    /** Keep the full terrain/collision footprint in the single prepared chunk. */
    public static boolean insideChunk(int x,int z){int localX=x&15,localZ=z&15;return localX>=2&&localX<=13&&localZ>=2&&localZ<=13;}
}
