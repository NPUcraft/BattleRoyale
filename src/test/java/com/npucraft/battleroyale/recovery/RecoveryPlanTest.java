package com.npucraft.battleroyale.recovery;
import com.npucraft.battleroyale.storage.RecoveryRepository;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RecoveryPlanTest {
 RecoveryRepository.Row row(String room,String world){return new RecoveryRepository.Row(UUID.randomUUID(),room,"map",world,"RUNNING",1,1,"{}","hash",0,"ACTIVE");}
 @Test void rejectsEveryConflictingRoomWithoutBlockingUnrelatedRoom(){var a=row("solo","one");var b=row("solo","two");var c=row("duo","three");assertEquals(Set.of(a.session(),b.session()),RecoveryPlan.duplicates(List.of(a,b,c)));}
 @Test void rejectsEveryConflictingWorld(){var a=row("solo","one");var b=row("duo","one");assertEquals(Set.of(a.session(),b.session()),RecoveryPlan.duplicates(List.of(a,b)));}
 @Test void independentRoomsRemainEligible(){assertTrue(RecoveryPlan.duplicates(List.of(row("solo","one"),row("duo","two"))).isEmpty());}
}
