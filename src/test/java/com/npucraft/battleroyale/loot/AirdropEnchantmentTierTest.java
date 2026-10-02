package com.npucraft.battleroyale.loot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AirdropEnchantmentTierTest {
    @Test void supplyLevelsHaveAUsefulFloorAndNeverExceedVanillaMaximum(){
        var random=new Random(27);
        for(var tier:AirdropEnchantmentTier.values())for(int maximum=1;maximum<=5;maximum++)for(int i=0;i<100;i++){
            int level=tier.level(maximum,random);
            assertTrue(level>=1&&level<=maximum);
            if(tier==AirdropEnchantmentTier.ENHANCED)assertTrue(level>=1&&level<=Math.min(2,maximum));
            if(tier==AirdropEnchantmentTier.BEST)assertEquals(maximum,level);
        }
        assertThrows(IllegalArgumentException.class,()->AirdropEnchantmentTier.BEST.level(0,random));
    }
    @Test void enhancedRareAndBestTiersAreReachableWithRareBestRolls(){
        var random=new Random(71);var counts=new int[3];
        for(int i=0;i<10_000;i++)counts[AirdropEnchantmentTier.roll(random).ordinal()]++;
        assertTrue(counts[0]>8200&&counts[0]<8800);
        assertTrue(counts[1]>1100&&counts[1]<1500);
        assertTrue(counts[2]>100&&counts[2]<300);
    }
}
