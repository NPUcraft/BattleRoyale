package com.lastsector.spawn;
import com.lastsector.room.SpawnSettings;
import com.lastsector.zone.Zone;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SpawnPlannerTest {
    @Test void exactlyMinimumHorizontalDistanceIsAccepted() {
        var random=new Random() {
            int index;
            final int[] coordinates={0,0,64,0};
            @Override public int nextInt(int origin,int bound) { return coordinates[index++]; }
        };
        var planner=new SpawnPlanner(new Zone(0,0,500),new SpawnSettings(64,1),2,random);
        planner.candidate(); planner.resolve(60.0);
        assertTrue(planner.spaced(planner.candidate())); planner.resolve(120.0);
        assertEquals(2,planner.plan().size());
    }
    @Test void completePlanIsInsideSpacedAndDeterministic() {
        var zone=new Zone(13.25,-57.25,500);
        var a=new SpawnPlanner(zone,SpawnSettings.DEFAULT,32,new Random(7));
        var b=new SpawnPlanner(zone,SpawnSettings.DEFAULT,32,new Random(7));
        while(!a.done()) {
            var c=a.candidate(); assertEquals(c,b.candidate()); a.resolve(70.0); b.resolve(70.0);
        }
        assertEquals(a.plan(),b.plan());
        for(var p:a.plan()) {
            assertTrue(zone.contains(p.x(),p.z()));
            for(var q:a.plan()) if(p!=q) assertTrue(Math.hypot(p.x()-q.x(),p.z()-q.z())>=64);
        }
    }
    @Test void impossibleSpacingFailsWithBoundedAttemptsAndNoPartialPlan() {
        var planner=new SpawnPlanner(new Zone(.5,.5,.1),new SpawnSettings(64,3),2,new Random(1));
        planner.candidate(); planner.resolve(60.0);
        for(int i=0;i<3;i++) { planner.candidate(); planner.resolve(60.0); }
        assertThrows(IllegalStateException.class,planner::candidate);
        assertThrows(IllegalStateException.class,planner::plan);
    }
    @Test void zeroDistanceStillRejectsDuplicatesAndUnsafeCandidates() {
        var planner=new SpawnPlanner(new Zone(.5,.5,.1),new SpawnSettings(0,3),2,new Random(1));
        planner.candidate(); planner.resolve(null);
        assertThrows(IllegalStateException.class,planner::plan);
        planner.candidate(); planner.resolve(64.0);
        var duplicate=planner.candidate(); assertFalse(planner.spaced(duplicate)); planner.resolve(64.0);
        assertFalse(planner.done());
    }
    @Test void unsafeTerrainCannotPublishAPlan() {
        var planner=new SpawnPlanner(new Zone(0,0,500),new SpawnSettings(0,2),1,new Random(1));
        planner.candidate(); planner.resolve(null); planner.candidate(); planner.resolve(Double.NaN);
        assertThrows(IllegalStateException.class,planner::candidate);
        assertThrows(IllegalStateException.class,planner::plan);
    }
}

