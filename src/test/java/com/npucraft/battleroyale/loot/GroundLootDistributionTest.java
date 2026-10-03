package com.npucraft.battleroyale.loot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundLootDistributionTest {
    @Test void fourHundredDisjointStrataCoverTheWholeInitialSquareRatherThanRandomClusters(){
        var bounds=new LootArea.Bounds(-1000,999,-1000,999);var cells=GroundLootDistribution.strata(bounds,400,new Random(19));
        assertEquals(400,cells.size());assertEquals(cells,GroundLootDistribution.strata(bounds,400,new Random(19)));
        long area=0;for(var a:cells){area+=(long)(a.maxX()-a.minX()+1)*(a.maxZ()-a.minZ()+1);assertTrue(a.minX()>=bounds.minX()&&a.maxX()<=bounds.maxX()&&a.minZ()>=bounds.minZ()&&a.maxZ()<=bounds.maxZ());}
        assertEquals(4_000_000,area);
        for(int i=0;i<cells.size();i++)for(int j=i+1;j<cells.size();j++){var a=cells.get(i);var b=cells.get(j);assertTrue(a.maxX()<b.minX()||b.maxX()<a.minX()||a.maxZ()<b.minZ()||b.maxZ()<a.minZ());}
    }
    @Test void narrowSmallAndNegativeBoundsRemainValidAndNeverRepeatTheSameStratum(){
        for(var bounds:List.of(new LootArea.Bounds(-3,-3,-100,100),new LootArea.Bounds(-100,100,-3,-3),new LootArea.Bounds(-2,2,-2,2))){
            var cells=GroundLootDistribution.strata(bounds,400,new Random(2));assertEquals(cells.size(),new HashSet<>(cells).size());
            assertEquals(Math.min(400,(bounds.maxX()-bounds.minX()+1)*(bounds.maxZ()-bounds.minZ()+1)),cells.size());
            for(var cell:cells)assertTrue(cell.minX()<=cell.maxX()&&cell.minZ()<=cell.maxZ());
        }
    }
    @Test void configuredRequestsAndRuntimeAttemptsAreIndependentlyBounded(){
        var large=new LootArea("a",0,1000,-64,319,0,1000,"basic",1,320,1024,10);
        assertDoesNotThrow(()->new MapLoot(List.of(),List.of(large,large)));
        assertThrows(IllegalArgumentException.class,()->new MapLoot(List.of(),List.of(large,large,large)));
        assertThrows(IllegalArgumentException.class,()->new LootArea("a",0,1,0,1,0,1,"basic",1,0,1025,10));
        var attempts=new GroundLootBudget();for(int i=0;i<GroundLootBudget.BASE_MAX_ATTEMPTS;i++)assertTrue(attempts.request(i));assertFalse(attempts.request(GroundLootBudget.BASE_MAX_ATTEMPTS+1));assertEquals(GroundLootBudget.BASE_MAX_ATTEMPTS,attempts.attempts());
        var elapsed=new GroundLootBudget();assertTrue(elapsed.request(100));assertFalse(elapsed.request(100+GroundLootBudget.MAX_NANOS));assertEquals(1,elapsed.attempts());
    }
    @Test void nativeContainerDefaultsAreFifteenPercentWithOneToTwoRolls(){
        var settings=AutoContainerLootSettings.DEFAULT;assertEquals(.15,settings.chance());assertEquals(1,settings.minRolls());assertEquals(2,settings.maxRolls());assertEquals(16,settings.maxContainersPerTick());
    }
}
