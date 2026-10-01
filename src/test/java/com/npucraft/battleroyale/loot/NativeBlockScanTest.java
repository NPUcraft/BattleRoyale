package com.npucraft.battleroyale.loot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeBlockScanTest {
    @Test void naturalOresAndAncientDebrisAreNotStorageBlocks(){
        for(var material:org.bukkit.Material.values())if(material.name().endsWith("_ORE")||material.name().equals("ANCIENT_DEBRIS"))assertFalse(ValuableBlockPolicy.replaces(material.name()),material.name());
        for(String value:List.of("IRON_BLOCK","GOLD_BLOCK","DIAMOND_BLOCK","EMERALD_BLOCK","NETHERITE_BLOCK","RAW_IRON_BLOCK","RAW_GOLD_BLOCK","RAW_COPPER_BLOCK"))assertTrue(ValuableBlockPolicy.replaces(value));
        assertEquals("STONE",ValuableBlockPolicy.replacement(0));assertEquals("DEEPSLATE",ValuableBlockPolicy.replacement(-1));
    }
    @Test void scanHasSeparateSampleAndWriteCapsAndResumesExactNextPosition(){
        var scan=new NativeBlockScan(-64,320,0,new int[0]);var touched=new ArrayList<Integer>();
        var batch=scan.advance(4096,256,index->{touched.add(index);return index%3==0;});
        assertEquals(256,batch.replaced());assertEquals(766,batch.visited());assertEquals(766,scan.cursor());
        var restored=new NativeBlockScan(-64,320,scan.cursor(),scan.exclusions());
        batch=restored.advance(4096,256,index->{assertTrue(index>=766);return false;});assertEquals(4096,batch.visited());assertEquals(0,batch.replaced());
    }
    @Test void playerAndGeneratedPositionsSurviveRestartAndAreNeverVisited(){
        var scan=new NativeBlockScan(-64,320,0,new int[0]);int player=scan.position(4,80,7),beacon=scan.position(7,90,7);
        scan.preserve(player);scan.preserve(beacon);int[] persisted=scan.exclusions();
        var resumed=new NativeBlockScan(-64,320,scan.cursor(),persisted);persisted[0]=0;
        var touched=new HashSet<Integer>();while(!resumed.done())resumed.advance(4096,256,index->{touched.add(index);return false;});
        assertFalse(touched.contains(player));assertFalse(touched.contains(beacon));assertEquals((320+64)*256-2,touched.size());
        assertFalse(resumed.original(player));assertEquals(0,resumed.exclusions().length);
        assertEquals(0,resumed.advance(4096,256,index->fail("Completed chunk cannot rescan later player blocks")).visited());
    }
    @Test void failedWriteDoesNotAdvanceCursorAndExclusionOverflowPreservesTheChunk(){
        var scan=new NativeBlockScan(0,32,0,new int[0]);assertThrows(IllegalStateException.class,()->scan.advance(4096,256,index->{throw new IllegalStateException();}));assertEquals(0,scan.cursor());
        for(int i=0;i<NativeBlockScan.MAX_EXCLUSIONS;i++)scan.preserve(i);scan.preserve(4096);assertTrue(scan.done());
    }
    @Test void corruptDurableStateIsRejectedInsteadOfResettingAndDestroyingPlayerChanges(){
        assertThrows(IllegalArgumentException.class,()->new NativeBlockScan(-64,320,-1,new int[0]));
        assertThrows(IllegalArgumentException.class,()->new NativeBlockScan(-64,320,0,new int[]{1,1}));
        assertThrows(IllegalArgumentException.class,()->new NativeBlockScan(-64,320,0,new int[]{98_304}));
        assertThrows(IllegalArgumentException.class,()->new NativeBlockScan(-2048,2049,0,new int[0]));
    }
}
