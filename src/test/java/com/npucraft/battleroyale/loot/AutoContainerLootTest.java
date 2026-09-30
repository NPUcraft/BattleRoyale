package com.npucraft.battleroyale.loot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoContainerLootTest {
    @Test void durableDecisionsCoverBothChestHalvesAndDoNotRerollAfterEmptyingOrRestoration(){
        var firstChunk=new ContainerLootLedger(new long[0]);var secondChunk=new ContainerLootLedger(new long[0]);
        assertTrue(firstChunk.mark(15,70,-1));assertTrue(secondChunk.mark(16,70,-1));
        var restoredFirst=new ContainerLootLedger(firstChunk.snapshot());var restoredSecond=new ContainerLootLedger(secondChunk.snapshot());
        assertTrue(restoredFirst.contains(15,70,-1));assertTrue(restoredSecond.contains(16,70,-1));
        assertFalse(restoredFirst.mark(15,70,-1));assertFalse(restoredSecond.mark(16,70,-1));
        // Placement at a used coordinate remains excluded even if the original tile was destroyed.
        assertEquals(1,restoredFirst.snapshot().length);assertEquals(1,restoredSecond.snapshot().length);
    }
    @Test void chunkLocalPackingHandlesNegativeWorldCoordinatesAndAllBlockHeightsWithoutCollisions(){
        var values=new HashSet<Long>();
        for(int x=-16;x<0;x++)for(int z=-16;z<0;z++)for(int y:List.of(-2048,-64,-1,0,1,319,2047))
            assertTrue(values.add(ContainerLootLedger.position(x,y,z)),x+","+y+","+z);
        assertEquals(16*16*7,values.size());
        // Each ledger belongs to its own chunk, so modulo coordinates intentionally match.
        assertEquals(ContainerLootLedger.position(-1,90,-1),ContainerLootLedger.position(15,90,15));
    }
    @Test void savedDecisionsAreDefensiveAndSeparateSessionsStartFresh(){
        var ledger=new ContainerLootLedger(new long[0]);ledger.mark(1,70,2);
        var saved=ledger.snapshot();saved[0]=0;assertTrue(ledger.contains(1,70,2));
        var restored=new ContainerLootLedger(ledger.snapshot());assertFalse(restored.mark(1,70,2));
        var nextMatch=new ContainerLootLedger(new long[0]);assertTrue(nextMatch.mark(1,70,2));
    }
    @Test void probabilityEndpointsAndDedicatedRollBoundsPreserveSourceTable(){
        var source=new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",1,1,1)));
        var settings=new AutoContainerLootSettings(true,"basic",1,2,5,16);
        var generated=settings.resolvedTable(Map.of("basic",source));var random=new Random(15);
        for(int i=0;i<200;i++){assertTrue(settings.activates(random));int count=generated.roll(random).size();assertTrue(count>=2&&count<=5);}
        assertEquals(1,source.roll(random).size());
        var disabledProbability=new AutoContainerLootSettings(true,"basic",0,2,5,16);
        for(int i=0;i<100;i++)assertFalse(disabledProbability.activates(random));
    }
    @Test void malformedChanceBudgetMissingTableAndExplosiveItemBatchesFailBeforeAPlayerCanStart(){
        assertThrows(IllegalArgumentException.class,()->new AutoContainerLootSettings(true,"basic",Double.NaN,2,5,16));
        assertThrows(IllegalArgumentException.class,()->new AutoContainerLootSettings(true,"basic",1.1,2,5,16));
        assertThrows(IllegalArgumentException.class,()->new AutoContainerLootSettings(true,"basic",.9,0,5,16));
        assertThrows(IllegalArgumentException.class,()->new AutoContainerLootSettings(true,"basic",.9,2,5,0));
        assertThrows(IllegalArgumentException.class,()->AutoContainerLootSettings.DEFAULT.resolvedTable(Map.of()));
        var large=new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",1,4096,4096)));
        assertThrows(IllegalArgumentException.class,()->AutoContainerLootSettings.DEFAULT.resolvedTable(Map.of("basic",large)));
    }
}
