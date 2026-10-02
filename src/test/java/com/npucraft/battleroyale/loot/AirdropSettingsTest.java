package com.npucraft.battleroyale.loot;

import java.util.List;
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
    @Test void announcementSecondsAndSpacingAreBoundedAndConfigurable() {
        assertEquals(60,AirdropSettings.DEFAULT.announcementSeconds());assertEquals(0,AirdropSettings.DEFAULT.minDistance());
        var custom=new AirdropSettings(true,"airdrop",6,8,8,24,120,120,128);
        assertEquals(120,custom.announcementSeconds());assertEquals(128,custom.minDistance());
        // The legacy seven-argument form keeps the historical sixty-second countdown and no fixed spacing.
        assertEquals(60,new AirdropSettings(true,"basic",1,5,8,24,120).announcementSeconds());
        for(int seconds:new int[]{0,4,601})assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,8,24,120,seconds,0));
        for(int distance:new int[]{-1,100_001})assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,8,24,120,60,distance));
    }
    @Test void roundTablesFallBackToTheSharedTableAndValidateTheirNames() {
        var ladder=new AirdropSettings(true,"airdrop",6,8,8,24,120,60,0,List.of("airdrop-1","airdrop-2","airdrop-3","airdrop-4"));
        assertEquals("airdrop-1",ladder.roundTable(0));assertEquals("airdrop-4",ladder.roundTable(3));
        assertEquals("airdrop",ladder.roundTable(4),"Rounds beyond the ladder share the default table");
        assertEquals("airdrop",ladder.roundTable(-1));
        assertEquals(List.of(),new AirdropSettings(true,"basic",1,5,8,24,120).roundTables(),"Legacy constructors stay table-uniform");
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,8,24,120,60,0,List.of("Bad Name")));
        assertThrows(IllegalArgumentException.class,()->new AirdropSettings(true,"basic",1,5,8,24,120,60,0,List.of("")));
    }
}
