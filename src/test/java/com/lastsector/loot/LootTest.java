package com.lastsector.loot;
import com.lastsector.zone.Zone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LootTest {
    LootTable.Entry bread=new LootTable.Entry("minecraft:bread",3,2,8);
    @Test void deterministicWeightedReplacementAndAmountBounds() {
        LootTable table=new LootTable("x",128,128,List.of(bread,new LootTable.Entry("minecraft:arrow",1,1,1)));
        var rolls=table.roll(new Random(91)); assertEquals(rolls,table.roll(new Random(91))); assertEquals(128,rolls.size());
        long breads=rolls.stream().filter(r->r.item().equals("minecraft:bread")).count(); assertTrue(breads>70 && breads<115);
        assertTrue(rolls.stream().allMatch(r->r.item().equals("minecraft:bread")?r.amount()>=2 && r.amount()<=8:r.amount()==1));
    }
    @Test void zeroRollsAndSplitting() {
        assertTrue(new LootTable("x",0,0,List.of()).roll(new Random(1)).isEmpty());
        assertEquals(List.of(64,64,2),LootTable.split(130,64)); assertEquals(List.of(1,1,1),LootTable.split(3,1));
        assertThrows(IllegalArgumentException.class,()->LootTable.split(1,0));
    }
    @ParameterizedTest @CsvSource({"-1,1","2,1","0,129"}) void invalidRolls(int min,int max) { assertThrows(IllegalArgumentException.class,()->new LootTable("x",min,max,List.of(bread))); }
    @Test void badWeightsAmountsKeysAndOverflowRejected() {
        assertThrows(IllegalArgumentException.class,()->new LootTable.Entry("minecraft:bread",0,1,1));
        assertThrows(IllegalArgumentException.class,()->new LootTable.Entry("minecraft:bread",1,0,1));
        assertThrows(IllegalArgumentException.class,()->new LootTable.Entry("minecraft:bread",1,2,1));
        assertThrows(IllegalArgumentException.class,()->new LootTable.Entry("bread",1,1,1));
        assertThrows(ArithmeticException.class,()->new LootTable("x",1,1,List.of(new LootTable.Entry("minecraft:bread",Long.MAX_VALUE,1,1),bread)));
    }
    LootArea area(double chance) { return new LootArea("a",-10,10,60,70,-10,10,"x",chance,2,4,3); }
    @Test void intersectionsKeepActualCoordinatesInsideInitialAndAllowPartialAreas() {
        var area=area(1); var initial=new Zone(10,0,5); var bounds=area.intersection(initial).orElseThrow();
        assertEquals(5,bounds.minX()); assertEquals(10,bounds.maxX());
        for(int x=bounds.minX();x<=bounds.maxX();x++) for(int z=bounds.minZ();z<=bounds.maxZ();z++) assertTrue(initial.contains(x+.5,z+.5));
        assertTrue(area.intersection(new Zone(100,100,2)).isEmpty());
    }
    @Test void probabilityEndpointsAndSpawnCount() {
        var random=new Random(1); for(int i=0;i<100;i++) { assertFalse(area(0).activates(random)); assertTrue(area(1).activates(random)); int n=area(.5).count(random); assertTrue(n>=2 && n<=4); }
        assertThrows(IllegalArgumentException.class,()->area(Double.NaN)); assertThrows(IllegalArgumentException.class,()->area(1.1));
    }
    @Test void sanitationOncePerChunkAndSeparateEntityArrival() {
        var ledger=new SanitationLedger(); int[] count={0}; ledger.blocks(1,()->count[0]++); ledger.blocks(1,()->fail());
        ledger.entities(1,()->count[0]++); ledger.entities(1,()->fail()); assertEquals(2,count[0]); assertEquals(1,ledger.chunks());
        var other=new SanitationLedger(); other.blocks(1,()->count[0]++); assertEquals(3,count[0]);
    }
    @Test void failedSanitationCanRetryAndReentrantInspectionDoesNotRepeat() {
        var ledger=new SanitationLedger(); assertThrows(IllegalStateException.class,()->ledger.blocks(1,()-> {throw new IllegalStateException();}));
        assertEquals(0,ledger.chunks()); ledger.blocks(1,()->ledger.blocks(1,()->fail())); assertEquals(1,ledger.chunks());
    }
}
