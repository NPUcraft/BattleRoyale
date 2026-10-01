package com.npucraft.battleroyale.loot;

import java.util.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LootRegionQualityTest {
    @Test void authoredSurfaceSignalsExcludeNaturalTerrainOresAndSanitizedResources(){
        for(String name:List.of("CRAFTING_TABLE","FURNACE","STONE_BRICKS","OAK_PLANKS","QUARTZ_BLOCK","WHITE_CONCRETE","OAK_STAIRS","IRON_BARS","DIRT_PATH"))assertTrue(LootRegionQualityPolicy.built(name),name);
        for(String name:List.of("STONE","GRASS_BLOCK","DIRT","SAND","SNOW_BLOCK","ICE","OAK_LOG","OAK_LEAVES","TERRACOTTA","ANCIENT_DEBRIS"))assertFalse(LootRegionQualityPolicy.built(name),name);
        for(Material material:Material.values())if(material.name().endsWith("_ORE"))assertFalse(LootRegionQualityPolicy.built(material.name()));
        for(String name:ValuableBlockPolicy.STORAGE)assertFalse(LootRegionQualityPolicy.built(name));
    }
    @Test void sixteenUniqueColumnsAndConservativeThresholdBoundAllSurfaceReads(){
        var points=new HashSet<String>();for(int n=0;n<16;n++){int x=LootRegionQualityPolicy.x(n),z=LootRegionQualityPolicy.z(n);assertTrue(x>=0&&x<16&&z>=0&&z<16);assertTrue(points.add(x+":"+z));}
        assertEquals(16,points.size());assertEquals(48,LootRegionQualityPolicy.MAX_BLOCK_READS);
        assertEquals(LootRegionQuality.NATURAL,LootRegionQualityPolicy.classify(3,16,.25));
        assertEquals(LootRegionQuality.BUILT,LootRegionQualityPolicy.classify(4,16,.25));
        assertEquals(LootRegionQuality.NATURAL,LootRegionQualityPolicy.classify(0,0,.25));
        assertThrows(IllegalArgumentException.class,()->LootRegionQualityPolicy.classify(17,16,.25));
    }
    @Test void persistedResultSurvivesThresholdChangesWithoutResamplingAndRejectsMalformedData(){
        var original=new LootRegionSample(LootRegionQuality.BUILT,16,5);
        assertEquals(original,LootRegionSample.decode(original.encode()));
        assertEquals(LootRegionQuality.NATURAL,LootRegionSample.RECOVERED_UNKNOWN.quality());
        for(int[] bad:new int[][]{{1,0,16},{2,0,16,0},{1,2,16,0},{1,0,17,0},{1,1,16,17},{1,1,0,0}})assertThrows(IllegalArgumentException.class,()->LootRegionSample.decode(bad));
        assertThrows(IllegalArgumentException.class,()->LootRegionSample.decode(null));
    }
    @Test void regionalEntrySelectionPreservesRequestedQuantityAndDisabledLegacyTables(){
        var original=new LootTable("custom",1,3,List.of(new LootTable.Entry("minecraft:bread",1,1,1)));
        var natural=new LootTable("region-natural",10,10,List.of(new LootTable.Entry("minecraft:stone_sword",1,1,1)));
        var built=new LootTable("region-built",20,20,List.of(new LootTable.Entry("minecraft:iron_sword",1,1,1)));
        var tables=Map.of(natural.id(),natural,built.id(),built);
        var resolved=LootRegionQualitySettings.DEFAULT.resolve(LootRegionQuality.BUILT,original,tables);
        assertEquals(1,resolved.minRolls());assertEquals(3,resolved.maxRolls());assertEquals(built.entries(),resolved.entries());
        assertEquals(natural.entries(),LootRegionQualitySettings.DEFAULT.resolve(LootRegionQuality.NATURAL,original,tables).entries());
        assertSame(original,LootRegionQualitySettings.DISABLED.resolve(LootRegionQuality.BUILT,original,Map.of()));
        LootRegionQualitySettings.DISABLED.validate(Map.of());
        assertThrows(IllegalArgumentException.class,()->LootRegionQualitySettings.DEFAULT.validate(Map.of()));
        var large=new LootTable("region-built",1,1,List.of(new LootTable.Entry("minecraft:arrow",1,1500,1500)));
        assertThrows(IllegalArgumentException.class,()->LootRegionQualitySettings.DEFAULT.resolve(LootRegionQuality.BUILT,original,Map.of("region-built",large)));
    }
    @Test void configurationCannotUseZeroInvalidOrUnboundedThresholds(){
        for(double value:new double[]{0,-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->new LootRegionQualitySettings(true,value,"basic","built"));
        assertThrows(IllegalArgumentException.class,()->new LootRegionQualitySettings(true,.25,"../bad","built"));
    }
}
