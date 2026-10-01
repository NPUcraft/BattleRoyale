package com.npucraft.battleroyale.loot;

import java.util.*;
import java.util.function.IntPredicate;

/** Durable chunk-local cursor. Positions already visited or explicitly changed by players are never revisited. */
public final class NativeBlockScan {
    public static final int MAX_EXCLUSIONS=4096;
    private final int minY,maxY,total;private int cursor;private final Set<Integer> excluded=new HashSet<>();
    public NativeBlockScan(int minY,int maxY,int cursor,int[] exclusions){
        if(minY>=maxY||(long)maxY-minY>4096)throw new IllegalArgumentException("Invalid sanitation world height");
        this.minY=minY;this.maxY=maxY;total=Math.multiplyExact(maxY-minY,256);
        if(cursor<0||cursor>total||exclusions.length>MAX_EXCLUSIONS)throw new IllegalArgumentException("Invalid sanitation cursor/exclusion count");
        this.cursor=cursor;
        for(int value:exclusions){if(value<0||value>=total||!excluded.add(value))throw new IllegalArgumentException("Invalid sanitation exclusion");}
    }
    public record Batch(int visited,int replaced) {}
    public Batch advance(int samples,int writes,IntPredicate replace){
        if(samples<1||writes<1)throw new IllegalArgumentException("Positive sanitation budget required");
        int visited=0,replaced=0;
        while(cursor<total&&visited<samples&&replaced<writes){
            if(!excluded.contains(cursor)&&replace.test(cursor))replaced++;
            cursor++;visited++;
        }
        if(done())excluded.clear();
        return new Batch(visited,replaced);
    }
    public void preserve(int position){
        if(position<0||position>=total)throw new IllegalArgumentException("Sanitation position outside chunk");
        if(position<cursor||done())return;
        if(!excluded.contains(position)&&excluded.size()>=MAX_EXCLUSIONS){finish();return;} // Preserve unusually modified chunks, never overwrite them.
        excluded.add(position);
    }
    public boolean original(int position){return position>=cursor&&position<total&&!excluded.contains(position);}
    public void finish(){cursor=total;excluded.clear();}
    public boolean done(){return cursor==total;}public int cursor(){return cursor;}public int minY(){return minY;}public int maxY(){return maxY;}
    public int[] exclusions(){return excluded.stream().mapToInt(Integer::intValue).sorted().toArray();}
    public int position(int x,int y,int z){if(y<minY||y>=maxY)throw new IllegalArgumentException("Height outside sanitation chunk");return (y-minY)*256+(z&15)*16+(x&15);}
    public int x(int position){return position&15;}public int z(int position){return (position>>4)&15;}public int y(int position){return minY+(position>>8);}
}
