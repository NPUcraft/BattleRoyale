package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.map.GameWorld;
import com.npucraft.battleroyale.recovery.SessionRecoverySnapshot;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperMatchesRecoveryZoneTest {
    @Test void runningAndShowcaseRecoveryRetainSavedInitialSquareAndShrinkProgress(){
        for(String phase:List.of("RUNNING","ENDING")){
            UUID id=UUID.randomUUID(),player=UUID.randomUUID(),team=UUID.randomUUID();
            var initial=new Zone(2300,-1700,500);var next=new Zone(2370,-1720,250);
            var zone=new SessionRecoverySnapshot.ZoneState(initial,ZoneGeometry.interpolate(initial,next,.5),initial,next,0,ZonePhase.SHRINKING,5_000_000_000L);
            var outcome=phase.equals("ENDING")?new SessionRecoverySnapshot.Outcome(Set.of(player),Set.of(team),false,"NORMAL",42):null;
            var saved=new SessionRecoverySnapshot(1,id,"solo","city","saved-world","saved-world",phase,2,10_000_000_000L,"rules",
                    List.of(new SessionRecoverySnapshot.Team(team,1,Set.of(player))),
                    List.of(new SessionRecoverySnapshot.Participant(player,"Player","ELIMINATED",team,0,0,null,null,0)),zone,3_000_000_000L,
                    List.of(),List.of(),Set.of(),Set.of(),"COMPLETE",outcome,phase.equals("ENDING")?20_000_000_000L:0);
            var map=TestSupport.map(Path.of("template"));var world=new GameWorld(id,"solo","saved-world",Path.of("runtime/saved-world"),map);
            var session=GameSession.recovered(saved,TestSupport.room("solo",1,8),world);
            // Recovered sessions already own their initial square; selecting a fresh one is illegal.
            assertThrows(IllegalStateException.class,()->session.initialZone(new Zone(0,0,500)));
            var profile=new ZoneProfile("default",List.of(new ZoneProfile.InitialSize(8,500)),
                    List.of(new ZoneProfile.Stage(Duration.ofSeconds(10),Duration.ofSeconds(10),250,1,0,1)));
            long now=500_000_000_000L;
            assertDoesNotThrow(()->PaperMatches.restoreZone(session,saved,profile,new Random(123),now));
            assertSame(initial,session.initialZone().orElseThrow());assertEquals(zone,session.zone().orElseThrow().snapshot());
            assertEquals(GameState.valueOf(phase),session.state());assertSame(world,session.gameWorld().orElseThrow());
            assertThrows(IllegalStateException.class,()->PaperMatches.restoreZone(session,saved,profile,new Random(456),now));
        }
    }
}
