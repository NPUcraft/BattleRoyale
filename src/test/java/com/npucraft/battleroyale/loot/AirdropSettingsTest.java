package com.npucraft.battleroyale.loot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirdropSettingsTest {
    @Test void defaultsEnableBoundedSupplyDropsAndZeroMarkerLifetimeIsAllowed() {
        var defaults=AirdropSettings.DEFAULT;
        assertTrue(defaults.enabled());assertEquals("basic",defaults.table());assertEquals(60,defaults.announcementSeconds());
        assertTrue(defaults.minRolls()>=1&&defaults.maxRolls()<=27);
        assertDoesNotThrow(()->new AirdropSettings(true,"basic",1,27,30,128,0));
        assertDoesNotThrow(()->new AirdropSettings(false,"basic",1,1,1,1,600));
    }
    @Test void invalidLootRollRangesCannotOverflowOrProduceAnEmptyDrop() {
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",0,5,8,24,120));
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",5,4,8,24,120));
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,28,8,24,120));
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,null,1,5,8,24,120));
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true," ",1,5,8,24,120));
    }
    @Test void asyncAttemptsVisualDurationAndMarkerDurationHaveHardBounds() {
        for(int fall:new int[]{0,31})assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,fall,24,120));
        for(int attempts:new int[]{0,129})assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,8,attempts,120));
        for(int marker:new int[]{-1,601})assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,8,24,marker));
    }
}
