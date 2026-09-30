package com.npucraft.battleroyale.loot;

import com.npucraft.battleroyale.paper.CelebrationEffects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HorseSpawnPolicyTest {
    @Test void defaultsIncreaseDensityWithoutReplenishingTheCumulativeBudget(){
        assertEquals(16,HorseSettings.DEFAULT.maxPerSession());assertEquals(15,HorseSettings.DEFAULT.intervalSeconds());
        var settings=new HorseSettings(true,64,15,24,8);var original=new HorseSpawnPolicy(settings,new double[0]);
        for(int i=0;i<64;i++)assertTrue(original.claim(i*8,0));
        assertFalse(original.available());assertFalse(original.claim(1024,0));
        var recovered=HorseSpawnPolicy.fromEncoded(settings,original.encodedSnapshot());
        assertEquals(64,recovered.count());assertEquals(128,recovered.encodedSnapshot().length);assertFalse(recovered.claim(1024,0));
        assertEquals(64,HorseSpawnPolicy.fromEncoded(HorseSettings.DEFAULT,original.encodedSnapshot()).count());
        assertThrows(IllegalArgumentException.class,()->HorseSpawnPolicy.fromEncoded(settings,new long[130]));
    }
    @Test void claimsRemainSpentAfterHorseRemovalAndAcrossRestoration(){
        var settings=new HorseSettings(true,2,30,24,64);var ledger=new HorseSpawnPolicy(settings,new double[0]);
        assertTrue(ledger.claim(0,0));assertFalse(ledger.claim(63,0));assertTrue(ledger.claim(64,0));assertFalse(ledger.available());
        var recovered=new HorseSpawnPolicy(settings,ledger.snapshot());assertEquals(2,recovered.count());assertFalse(recovered.claim(128,0));
        double[] snapshot=ledger.snapshot();snapshot[0]=900;assertFalse(ledger.separated(0,0));
    }
    @Test void malformedLedgerFailsClosedAndLoweredCapDoesNotDiscardSavedClaims(){
        var settings=HorseSettings.DEFAULT;
        assertThrows(IllegalArgumentException.class,()->new HorseSpawnPolicy(settings,null));
        assertThrows(IllegalArgumentException.class,()->new HorseSpawnPolicy(settings,new double[]{1}));
        assertThrows(IllegalArgumentException.class,()->new HorseSpawnPolicy(settings,new double[]{Double.NaN,1}));
        assertThrows(IllegalArgumentException.class,()->new HorseSpawnPolicy(settings,new double[130]));
        var recovered=new HorseSpawnPolicy(new HorseSettings(true,1,30,24,64),new double[]{0,0,64,0});assertEquals(2,recovered.count());assertFalse(recovered.available());
    }
    @Test void longArrayCodecPreservesExactCoordinatesAndRejectsDamagedPayloads(){
        var settings=new HorseSettings(true,2,30,24,8);var ledger=new HorseSpawnPolicy(settings,new double[0]);
        assertTrue(ledger.claim(-29_999_999.5,-.5));assertTrue(ledger.claim(29_999_999.5,.5));
        long[] encoded=ledger.encodedSnapshot();var restored=HorseSpawnPolicy.fromEncoded(settings,encoded);
        assertArrayEquals(ledger.snapshot(),restored.snapshot());assertFalse(restored.available());
        assertEquals(Double.doubleToLongBits(-29_999_999.5),encoded[0]);encoded[0]=0;
        assertArrayEquals(ledger.snapshot(),restored.snapshot());
        assertThrows(IllegalArgumentException.class,()->HorseSpawnPolicy.fromEncoded(settings,null));
        assertThrows(IllegalArgumentException.class,()->HorseSpawnPolicy.fromEncoded(settings,new long[]{0}));
        assertThrows(IllegalArgumentException.class,()->HorseSpawnPolicy.fromEncoded(settings,new long[]{Double.doubleToLongBits(Double.NaN),0}));
        assertThrows(IllegalArgumentException.class,()->HorseSpawnPolicy.fromEncoded(settings,new long[]{Double.doubleToLongBits(Double.POSITIVE_INFINITY),0}));
        assertThrows(IllegalArgumentException.class,()->HorseSpawnPolicy.fromEncoded(settings,new long[]{Double.doubleToLongBits(30_000_001),0}));
        assertEquals(0,HorseSpawnPolicy.fromEncoded(settings,new long[0]).count());
    }
    @Test void singlePreparedChunkAlwaysContainsTheFullFootprintIncludingNegativeCoordinates(){
        for(int x=-32;x<32;x++)for(int z=-32;z<32;z++)if(HorseSpawnPolicy.insideChunk(x,z)){
            assertEquals(x>>4,(x-2)>>4);assertEquals(x>>4,(x+2)>>4);assertEquals(z>>4,(z-2)>>4);assertEquals(z>>4,(z+2)>>4);
        }
        assertFalse(HorseSpawnPolicy.insideChunk(-1,7));assertFalse(HorseSpawnPolicy.insideChunk(16,7));assertTrue(HorseSpawnPolicy.insideChunk(-3,7));
    }
    @Test void invalidSettingsAreRejectedAndDisabledGenerationCannotClaim(){
        assertThrows(IllegalArgumentException.class,()->new HorseSettings(true,65,30,24,64));
        assertThrows(IllegalArgumentException.class,()->new HorseSettings(true,6,4,24,64));
        assertThrows(IllegalArgumentException.class,()->new HorseSettings(true,6,30,129,64));
        assertThrows(IllegalArgumentException.class,()->new HorseSettings(true,6,30,24,Double.POSITIVE_INFINITY));
        assertFalse(new HorseSpawnPolicy(new HorseSettings(false,6,30,24,64),new double[0]).claim(0,0));
    }
    @Test void celebrationBudgetStaysBoundedForTiesAndVeryLargeTeams(){
        assertEquals(0,CelebrationEffects.rocketsPerWave(0));assertEquals(2,CelebrationEffects.rocketsPerWave(1));
        assertEquals(8,CelebrationEffects.rocketsPerWave(4));assertEquals(8,CelebrationEffects.rocketsPerWave(Integer.MAX_VALUE));
        assertEquals(3,CelebrationEffects.WAVES);
    }
}
