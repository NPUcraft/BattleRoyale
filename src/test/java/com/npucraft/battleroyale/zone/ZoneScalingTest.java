package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.recovery.SessionRecoverySnapshot.ZoneState;
import com.npucraft.battleroyale.recovery.SnapshotCodec;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ZoneScalingTest {
    private static ZoneProfile.Stage stage(int wait, int shrink, double half) {
        return new ZoneProfile.Stage(Duration.ofSeconds(wait),Duration.ofSeconds(shrink),half,1,.01,6);
    }
    static ZoneProfile profile() {
        return new ZoneProfile("small",List.of(new ZoneProfile.InitialSize(4,200),new ZoneProfile.InitialSize(8,300),
                new ZoneProfile.InitialSize(16,500),new ZoneProfile.InitialSize(32,750)),
                List.of(stage(90,60,600),stage(60,45,300),stage(60,30,100),stage(60,25,0)),750,Map.of());
    }
    @ParameterizedTest @CsvSource({"1,200","4,200","5,300","8,300","9,500","16,500","17,750","32,750"})
    void actualPopulationSelectsBucketAndEveryPhaseRemainsContainedUntilZero(int count,double half) {
        var profile=profile();assertEquals(half,profile.initialHalfSize(count));
        var zone=new ZoneRuntime(new Zone(951,862,half),profile,new Random(4),0);
        assertEquals(half*.8,zone.next().halfSize(),1e-9);
        for(int second=0;second<=430;second++) {
            zone.update(second*1_000_000_000L);var saved=zone.snapshot();
            assertTrue(zone.current().minX()>=saved.from().minX()-1e-8);
            assertTrue(zone.current().maxX()<=saved.from().maxX()+1e-8);
            assertTrue(zone.current().minZ()>=saved.from().minZ()-1e-8);
            assertTrue(zone.current().maxZ()<=saved.from().maxZ()+1e-8);
            assertEquals(4,zone.stageCount());
            if(second<430)assertNotEquals(ZonePhase.FINAL,zone.phase());
        }
        assertEquals(ZonePhase.FINAL,zone.phase());assertEquals(0,zone.current().halfSize());
        assertEquals(600,profile.stages().getFirst().targetHalfSize(),"Session scaling must not mutate configured targets or rules hash");
        var resolved=profile.resolved(half);assertSame(resolved,resolved.resolved(half),"Each already resolved instance is idempotent");
    }
    @Test void restoringAllPhasesUsesSavedInitialSizeAndNeverResamplesAnAnnouncedTarget() {
        var profile=profile();String rulesHash=SnapshotCodec.hash(profile.toString());var codec=new SnapshotCodec();
        for(int second:new int[]{0,90,111,150,210,222,255,315,328,345,405,416,430}) {
            var live=new ZoneRuntime(new Zone(951,862,200),profile,new Random(4),0);live.update(second*1_000_000_000L);
            var payload=codec.encode(live.snapshot());var saved=codec.decode(payload.version(),payload.json(),payload.checksum(),ZoneState.class);
            var restored=ZoneRuntime.restore(saved,profile,new Random(99),900_000_000_000L);
            assertEquals(saved,restored.snapshot());assertEquals(live.stage(),restored.stage());
            assertEquals(rulesHash,SnapshotCodec.hash(profile.toString()));
        }
        var live=new ZoneRuntime(new Zone(951,862,200),profile,new Random(4),0);live.update(111_000_000_000L);
        var restored=ZoneRuntime.restore(live.snapshot(),profile,new Random(99),900_000_000_000L);
        live.update(116_000_000_000L);restored.update(905_000_000_000L);assertEquals(live.current(),restored.current());
    }
    @Test void scaledLegacyPositiveFinalTailRetainsLongRemainingTimeAcrossRestore() {
        var base=profile();var stages=new ArrayList<>(base.stages());stages.set(3,stage(60,25,80));
        var profile=new ZoneProfile(base.id(),base.initialSizes(),stages,750,Map.of());
        var live=new ZoneRuntime(new Zone(0,0,200),profile,new Random(3),0);live.update(435_000_000_000L);
        assertEquals(ZonePhase.SHRINKING,live.phase());assertEquals(95,live.remainingSeconds(),1e-7);
        var restored=ZoneRuntime.restore(live.snapshot(),profile,new Random(20),0);assertEquals(live.snapshot(),restored.snapshot());
        restored.update(95_000_000_000L);assertEquals(ZonePhase.FINAL,restored.phase());assertEquals(0,restored.current().halfSize());
    }
    @Test void legacyRulesHashSpellingAndAbsoluteTargetsArePreserved() {
        var legacy=new ZoneProfile("legacy",List.of(new ZoneProfile.InitialSize(8,500)),List.of(stage(5,10,400)));
        String saved="ZoneProfile[id=legacy, initialSizes=[InitialSize[maxPlayers=8, halfSize=500.0]], stages=[Stage[waitDuration=PT5S, shrinkDuration=PT10S, targetHalfSize=400.0, baseDamagePerSecond=1.0, extraDamagePerBlock=0.01, maxDamagePerSecond=6.0]]]";
        assertEquals(saved,legacy.toString());assertEquals(SnapshotCodec.hash(saved),SnapshotCodec.hash(legacy.toString()));
        assertSame(legacy,legacy.resolved(750));assertEquals(400,legacy.resolved(750).stages().getFirst().targetHalfSize());
        assertEquals(saved,new ZoneProfile(legacy.id(),legacy.initialSizes(),legacy.stages(),0,Map.of()).toString());
        // Older native fixtures and existing explicitly supplied legacy initial squares remain accepted.
        var tiny=new ZoneProfile("tiny",legacy.initialSizes(),List.of(stage(1,1,12)));
        assertEquals(12,new ZoneRuntime(new Zone(0,0,24),tiny,new Random(1),0).next().halfSize());
    }
    @Test void minimumAndCoverageRemainStrict() {
        assertEquals(128,new ZoneProfile.InitialSize(4,128).halfSize());
        assertThrows(IllegalArgumentException.class,()->new ZoneProfile.InitialSize(4,127));
        assertThrows(IllegalArgumentException.class,()->profile().initialHalfSize(0));
        assertThrows(IllegalArgumentException.class,()->profile().initialHalfSize(33));
        assertThrows(IllegalArgumentException.class,()->profile().resolved(0));
        assertThrows(IllegalArgumentException.class,()->profile().resolved(Double.NaN));
    }
}
