package com.npucraft.battleroyale.loot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MobLootTest {
    @Test void onlyNaturalOriginsQualifyAndEveryPlayerOrFarmOriginFailsClosed(){
        assertTrue(MobLootPolicy.natural("NATURAL"));assertTrue(MobLootPolicy.natural("CHUNK_GEN"));
        for(String reason:List.of("SPAWNER","TRIAL_SPAWNER","SPAWNER_EGG","DISPENSE_EGG","CUSTOM","COMMAND","DEFAULT",
                "BREEDING","SLIME_SPLIT","REINFORCEMENTS","INFECTION","CURED","BUILD_IRONGOLEM","BUCKET","UNKNOWN"))
            assertFalse(MobLootPolicy.natural(reason),reason);
        assertFalse(MobLootPolicy.natural(null));
    }
    @Test void persistedCountAndLastAttemptEnforceCapCooldownAndClockRollback(){
        var settings=new MobLootSettings(true,"basic",.35,1,1,64,10);
        assertTrue(MobLootPolicy.canAttempt(settings,0,1000,Long.MIN_VALUE));
        assertFalse(MobLootPolicy.canAttempt(settings,63,10_999,1000));
        assertTrue(MobLootPolicy.canAttempt(settings,63,11_000,1000));
        assertFalse(MobLootPolicy.canAttempt(settings,64,11_000,1000));
        assertFalse(MobLootPolicy.canAttempt(settings,-1,11_000,1000));
        assertFalse(MobLootPolicy.canAttempt(settings,0,999,1000));
        assertFalse(MobLootPolicy.canAttempt(new MobLootSettings(false,"basic",1,1,1,64,10),0,11_000,Long.MIN_VALUE));
        // Recreating the service passes the saved primitive values; it cannot reset their limits.
        var reloaded=new MobLootSettings(true,"basic",.35,1,1,64,10);
        assertFalse(MobLootPolicy.canAttempt(reloaded,64,500_000,Long.MIN_VALUE));
        assertFalse(MobLootPolicy.canAttempt(reloaded,0,10_999,1000));
    }
    @Test void extraDropsClampLargeStacksAndPreserveOriginalTables(){
        var source=new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",7,1,4096),new LootTable.Entry("minecraft:arrow",3,32,64)));
        var settings=new MobLootSettings(true,"basic",1,2,3,64,10);var resolved=settings.resolvedTable(Map.of("basic",source));
        assertEquals(1,source.maxRolls());assertEquals(4096,source.entries().getFirst().maxAmount());
        assertEquals(7,resolved.entries().getFirst().weight());assertEquals(1,resolved.entries().getFirst().minAmount());
        assertEquals(8,resolved.entries().getLast().minAmount());assertEquals(8,resolved.entries().getLast().maxAmount());
        var random=new Random(6);for(int i=0;i<200;i++){
            var rolls=resolved.roll(random);assertTrue(rolls.size()>=2&&rolls.size()<=3);
            assertTrue(rolls.stream().allMatch(roll->roll.amount()>=1&&roll.amount()<=8));
        }
    }
    @Test void badSettingsAndMissingTablesFailBeforeTheFirstKill(){
        assertThrows(IllegalArgumentException.class,()->new MobLootSettings(true,"basic",Double.NaN,1,1,64,10));
        assertThrows(IllegalArgumentException.class,()->new MobLootSettings(true,"basic",1.1,1,1,64,10));
        assertThrows(IllegalArgumentException.class,()->new MobLootSettings(true,"basic",.35,1,4,64,10));
        assertThrows(IllegalArgumentException.class,()->new MobLootSettings(true,"basic",.35,1,1,257,10));
        assertThrows(IllegalArgumentException.class,()->new MobLootSettings(true,"basic",.35,1,1,64,0));
        assertThrows(IllegalArgumentException.class,()->MobLootSettings.DEFAULT.resolvedTable(Map.of()));
        assertDoesNotThrow(()->new MobLootSettings(true,"basic",0,1,1,64,10));
    }
}
