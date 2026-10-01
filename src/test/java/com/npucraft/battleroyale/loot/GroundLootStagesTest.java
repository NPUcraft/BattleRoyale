package com.npucraft.battleroyale.loot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundLootStagesTest {
    private final GroundLootSettings settings=new GroundLootSettings(true,"ground-early",List.of(
            new GroundLootSettings.Refill(1,"ground-mid",3),
            new GroundLootSettings.Refill(2,"ground-mid",3),
            new GroundLootSettings.Refill(3,"ground-late",2)));
    @Test void firstObservationOnlyRecordsTheOpeningStage() {
        var stages=new GroundLootStages(settings);
        assertEquals(-1,stages.lastStage());
        assertTrue(stages.onStage(1).isEmpty());
        assertEquals(1,stages.lastStage());
        // A repeat of the same stage is idempotent: no refill is ever queued twice.
        assertTrue(stages.onStage(1).isEmpty());
    }
    @Test void eachCompletedShrinkQueuesExactlyItsRefillOnce() {
        var stages=new GroundLootStages(settings);
        stages.onStage(1);
        var second=stages.onStage(2);
        assertEquals(1,second.size());assertEquals("ground-mid",second.getFirst().table());assertEquals(3,second.getFirst().points());
        assertTrue(stages.onStage(2).isEmpty(),"Repeated stage changes never re-queue");
        var third=stages.onStage(3);
        assertEquals(1,third.size());assertEquals("ground-mid",third.getFirst().table());
        var fourth=stages.onStage(4);
        assertEquals(1,fourth.size());assertEquals("ground-late",fourth.getFirst().table());assertEquals(2,fourth.getFirst().points());
        assertTrue(stages.onStage(5).isEmpty(),"Stages without refills do nothing");
    }
    @Test void recoveryStartingMidMatchStillOnlyFiresTheNextRefill() {
        var stages=new GroundLootStages(settings);
        assertTrue(stages.onStage(3).isEmpty(),"The recovery observation never replays earlier refills");
        assertEquals(1,stages.onStage(4).size());
    }
}
