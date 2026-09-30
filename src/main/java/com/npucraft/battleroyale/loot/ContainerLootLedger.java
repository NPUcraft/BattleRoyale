package com.npucraft.battleroyale.loot;

import java.util.HashSet;
import java.util.Set;

/** Chunk-local tombstones survive emptying, replacing, breaking, and reloading a container. */
public final class ContainerLootLedger {
    private final Set<Long> decisions=new HashSet<>();
    public ContainerLootLedger(long[] saved){for(long value:saved)decisions.add(value);}
    public boolean contains(int x,int y,int z){return decisions.contains(position(x,y,z));}
    public boolean mark(int x,int y,int z){return decisions.add(position(x,y,z));}
    public long[] snapshot(){return decisions.stream().mapToLong(Long::longValue).sorted().toArray();}
    public static long position(int x,int y,int z){return ((long)y<<8)|((z&15)<<4)|(x&15);}
}
