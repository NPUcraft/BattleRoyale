package com.npucraft.battleroyale.loot;

import java.util.*;
import java.util.random.RandomGenerator;

/** One random candidate per disjoint stratum, spreading finite requests over the playable intersection. */
public final class GroundLootDistribution {
    private GroundLootDistribution() {}
    public static List<LootArea.Bounds> strata(LootArea.Bounds area,int requested,RandomGenerator random) {
        if(requested<0||requested>MapLoot.MAX_GROUND_REQUESTS)throw new IllegalArgumentException("Ground candidate budget");
        long width=(long)area.maxX()-area.minX()+1,depth=(long)area.maxZ()-area.minZ()+1;
        if(width<1||depth<1)throw new IllegalArgumentException("Empty ground bounds");
        int count=(int)Math.min(requested,Math.min((long)Integer.MAX_VALUE,Math.min(width,requested)*Math.min(depth,requested)));
        if(count==0)return List.of();
        int columns=(int)Math.max(1,Math.min(Math.min(width,count),Math.round(Math.sqrt(count*(double)width/depth))));
        int rows=(int)Math.min(depth,(count+columns-1)/columns);
        columns=(int)Math.min(width,(count+rows-1)/rows);
        var cells=new ArrayList<LootArea.Bounds>();
        for(int z=0;z<rows;z++)for(int x=0;x<columns;x++)
            cells.add(new LootArea.Bounds((int)(area.minX()+width*x/columns),(int)(area.minX()+width*(x+1)/columns-1),
                    (int)(area.minZ()+depth*z/rows),(int)(area.minZ()+depth*(z+1)/rows-1)));
        for(int i=cells.size()-1;i>0;i--)Collections.swap(cells,i,random.nextInt(i+1));
        return List.copyOf(cells.subList(0,count));
    }
}
