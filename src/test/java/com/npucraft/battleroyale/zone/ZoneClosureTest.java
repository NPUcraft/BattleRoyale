package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.recovery.SessionRecoverySnapshot.ZoneState;
import com.npucraft.battleroyale.recovery.SnapshotCodec;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZoneClosureTest {
    private static ZoneProfile.Stage stage(int wait,int shrink,double target){
        return new ZoneProfile.Stage(Duration.ofSeconds(wait),Duration.ofSeconds(shrink),target,4,.03,20);
    }
    private static ZoneProfile profile(double target){
        return new ZoneProfile("closure",List.of(new ZoneProfile.InitialSize(8,500)),List.of(stage(5,10,400),stage(5,10,target)));
    }
    private static ZoneRuntime runtime(ZoneProfile profile){return new ZoneRuntime(new Zone(20,-30,500),profile,new Random(7),0);}
    private static ZoneState roundTrip(ZoneState saved){
        var codec=new SnapshotCodec();var payload=codec.encode(saved);
        assertEquals(1,payload.version());return codec.decode(payload.version(),payload.json(),payload.checksum(),ZoneState.class);
    }
    @Test void explicitZeroShrinksContinuouslyToAnEmptyZoneWithinConfiguredTime(){
        var live=runtime(profile(0));live.update(20_000_000_000L);Zone from=live.current(),target=live.next();
        assertEquals(0,target.halfSize());assertEquals(400,from.halfSize());
        for(int second=20;second<=30;second++){
            live.update(second*1_000_000_000L);assertEquals(400*(30-second)/10.0,live.current().halfSize(),1e-9);
            assertTrue(live.current().minX()>=from.minX()-1e-9);assertTrue(live.current().maxX()<=from.maxX()+1e-9);
            assertTrue(live.current().minZ()>=from.minZ()-1e-9);assertTrue(live.current().maxZ()<=from.maxZ()+1e-9);
            assertEquals(2,live.stageNumber());assertEquals(2,live.stageCount());
        }
        assertEquals(target,live.current());assertEquals(ZonePhase.FINAL,live.phase());assertNull(live.next());
        assertFalse(live.current().contains(target.centerX(),target.centerZ()));
        var restored=ZoneRuntime.restore(roundTrip(live.snapshot()),profile(0),new Random(2),900_000_000_000L);
        assertEquals(live.snapshot(),restored.snapshot());
    }
    @Test void legacyTargetContinuesWithoutPauseOrNewRoundAndKeepsTheSameShrinkSpeed(){
        var profile=profile(250);var live=runtime(profile);live.update(20_000_000_000L);Zone announced=live.next();
        live.update(29_000_000_000L);assertEquals(265,live.current().halfSize(),1e-8);
        live.update(30_000_000_000L);assertEquals(announced,live.current());assertEquals(ZonePhase.SHRINKING,live.phase());
        assertEquals(announced.centerX(),live.next().centerX());assertEquals(announced.centerZ(),live.next().centerZ());assertEquals(0,live.next().halfSize());
        assertEquals(250/15.0,live.remainingSeconds(),1e-8);assertEquals(2,live.stageNumber());
        live.update(31_000_000_000L);assertEquals(235,live.current().halfSize(),1e-8);
        live.update(35_000_000_000L);assertEquals(175,live.current().halfSize(),1e-8);
        live.update(47_000_000_000L);assertEquals(0,live.current().halfSize());assertEquals(ZonePhase.FINAL,live.phase());
        assertEquals(250,profile.stages().getLast().targetHalfSize(),"Do not rewrite a loaded profile or its recovery rules hash");
    }
    @Test void tailCheckpointWithRemainingLongerThanOriginalShrinkSurvivesCodecAndDowntime(){
        var profile=profile(250);var live=runtime(profile);live.update(31_000_000_000L);var saved=roundTrip(live.snapshot());
        assertTrue(saved.remainingNanos()>profile.stages().getLast().shrinkDuration().toNanos());
        long now=900_000_000_000L;var restored=ZoneRuntime.restore(saved,profile,new Random(100),now);
        assertEquals(saved.current(),restored.current());assertEquals(saved.next(),restored.next());assertEquals(saved.remainingNanos(),restored.snapshot().remainingNanos());
        live.update(36_000_000_000L);restored.update(now+5_000_000_000L);assertEquals(live.current(),restored.current());
        assertEquals(live.snapshot().remainingNanos(),restored.snapshot().remainingNanos());
        var again=ZoneRuntime.restore(roundTrip(restored.snapshot()),profile,new Random(200),0);again.update(20_000_000_000L);
        assertEquals(ZonePhase.FINAL,again.phase());assertEquals(0,again.current().halfSize());
    }
    @Test void legacyStationaryFinalStartsAtItsSavedSquareAndClosesWithoutJumping(){
        var profile=profile(250);Zone initial=new Zone(20,-30,500),end=new Zone(50,60,250);
        var saved=roundTrip(new ZoneState(initial,end,end,null,1,ZonePhase.FINAL,0));
        var restored=ZoneRuntime.restore(saved,profile,new Random(4),100_000_000_000L);
        assertEquals(end,restored.current());assertSame(saved.initial(),restored.initial());assertEquals(ZonePhase.SHRINKING,restored.phase());
        assertEquals(new Zone(50,60,0),restored.next());assertEquals(250/15.0,restored.remainingSeconds(),1e-8);
        restored.update(105_000_000_000L);assertEquals(175,restored.current().halfSize(),1e-8);
        assertEquals(50,restored.current().centerX());assertEquals(60,restored.current().centerZ());
    }
    @Test void legacyWaitingAndShrinkingSnapshotsRetainPreviouslyAnnouncedTargets(){
        var profile=profile(250);
        for(long second:new long[]{15,20,24,29}){
            var live=runtime(profile);live.update(second*1_000_000_000L);var saved=roundTrip(live.snapshot());
            var restored=ZoneRuntime.restore(saved,profile,new Random(9),900_000_000_000L);
            assertEquals(saved,restored.snapshot());assertEquals(250,restored.next().halfSize());
        }
    }
    @Test void pathologicalLegacyTailIsBoundedAndBadRemainingIsRejected(){
        var profile=new ZoneProfile("slow",List.of(new ZoneProfile.InitialSize(8,500)),List.of(stage(0,1,Math.nextDown(500.0))));
        var live=runtime(profile);live.update(1_000_000_000L);
        assertEquals(86_400,live.remainingSeconds());assertEquals(ZoneRuntime.MAX_FINAL_CLOSURE_NANOS,live.snapshot().remainingNanos());
        var saved=live.snapshot();var invalid=new ZoneState(saved.initial(),saved.current(),saved.from(),saved.next(),saved.stage(),saved.phase(),saved.remainingNanos()+1);
        assertThrows(IllegalArgumentException.class,()->ZoneRuntime.restore(invalid,profile,new Random(2),0));
        live.update(86_401_000_000_000L);assertEquals(ZonePhase.FINAL,live.phase());assertEquals(0,live.current().halfSize());
    }
    @Test void oneLongTickAndFineTicksReachTheSameClosedPoint(){
        var profile=profile(250);var fine=runtime(profile);var jumped=runtime(profile);
        for(int tenth=0;tenth<=500;tenth++)fine.update(tenth*100_000_000L);
        jumped.update(50_000_000_000L);assertEquals(fine.snapshot(),jumped.snapshot());assertEquals(0,jumped.current().halfSize());
    }
    @Test void collapsedCenterAlwaysTakesDamageIncludingZeroDamageLegacyProfiles(){
        var empty=new Zone(10,20,0);var normal=stage(0,1,0);
        assertEquals(4,ZoneDamage.amount(empty,10,20,normal));assertEquals(4.15,ZoneDamage.amount(empty,13,24,normal),1e-9);
        assertEquals(20,ZoneDamage.amount(empty,10000,20,normal));
        var zero=new ZoneProfile.Stage(Duration.ZERO,Duration.ofSeconds(1),0,0,0,0);
        assertEquals(1,ZoneDamage.amount(empty,10,20,zero));assertEquals(1,ZoneDamage.amount(empty,10000,20,zero));
        assertEquals(0,ZoneDamage.amount(new Zone(10,20,1),10,20,normal));
    }
    @Test void zeroIsOnlyValidForTheFinalStageAndInitialZoneMustRemainPositive(){
        assertThrows(IllegalArgumentException.class,()->new ZoneProfile("bad",List.of(new ZoneProfile.InitialSize(8,500)),List.of(stage(0,1,0),stage(0,1,0))));
        assertThrows(IllegalArgumentException.class,()->ZoneGeometry.initial(new com.npucraft.battleroyale.map.PlayableArea(-500,500,-500,500),0,new Random(1)));
    }
}
