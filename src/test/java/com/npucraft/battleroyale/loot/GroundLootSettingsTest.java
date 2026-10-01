package com.npucraft.battleroyale.loot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GroundLootSettingsTest {
    private final LootTable table=new LootTable("ground-early",2,3,List.of(new LootTable.Entry("minecraft:bread",1,1,1)));
    private final Map<String,LootTable> tables=Map.of("ground-early",table,"ground-mid",new LootTable("ground-mid",2,3,table.entries()),"ground-late",new LootTable("ground-late",2,3,table.entries()));
    @Test void disabledSettingsAreNeverValidatedForBackwardsCompatibility() {
        assertEquals(GroundLootSettings.DISABLED,GroundLootSettings.DISABLED);
        assertDoesNotThrow(()->GroundLootSettings.DISABLED.validate(Map.of()));
        assertFalse(GroundLootSettings.DISABLED.enabled());
        assertTrue(GroundLootSettings.DISABLED.refills().isEmpty());
    }
    @Test void enabledSettingsRequireExistingTables() {
        var valid=new GroundLootSettings(true,"ground-early",List.of(new GroundLootSettings.Refill(1,"ground-mid",3),new GroundLootSettings.Refill(3,"ground-late",2)));
        assertDoesNotThrow(()->valid.validate(tables));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings(true,"missing",List.of()).validate(tables));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings(true,"ground-early",List.of(new GroundLootSettings.Refill(1,"missing",3))).validate(tables));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings(true,"",List.of()));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings(true,null,List.of()));
    }
    @Test void refillsBoundStageTableAndPoints() {
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings.Refill(0,"ground-mid",1));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings.Refill(1,"ground mid",1));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings.Refill(1,"ground-mid",0));
        assertThrows(IllegalArgumentException.class,()->new GroundLootSettings.Refill(1,"ground-mid",65));
        assertDoesNotThrow(()->new GroundLootSettings.Refill(1,"ground-mid",64));
    }
}
