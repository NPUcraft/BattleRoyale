package com.npucraft.lastsector.zone;
import com.npucraft.lastsector.map.PlayableArea;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
class ZoneRuntimeTest {
    static ZoneProfile.Stage stage(int wait,int shrink,double half) {
        return new ZoneProfile.Stage(Duration.ofSeconds(wait),Duration.ofSeconds(shrink),half,1,.01,6);
    }
    static ZoneProfile profile() {
        return new ZoneProfile("test",List.of(new ZoneProfile.InitialSize(8,500),new ZoneProfile.InitialSize(16,750),
                new ZoneProfile.InitialSize(32,1000)),List.of(stage(5,10,400),stage(5,10,250)));
    }
    @ParameterizedTest @CsvSource({"1,500","8,500","9,750","16,750","17,1000","32,1000"})
    void initialBucketsUseActualCount(int count,double half) { assertEquals(half,profile().initialHalfSize(count)); }
    @Test void recoveryPausesDowntimeDuringShrink() {
        var live=new ZoneRuntime(new Zone(0,0,500),profile(),new Random(1),0);live.update(8_000_000_000L);var saved=live.snapshot();
        var restored=ZoneRuntime.restore(saved,profile(),new Random(2),999_000_000_000L);
        assertEquals(saved.current(),restored.current());assertEquals(7,restored.remainingSeconds());assertEquals(ZonePhase.SHRINKING,restored.phase());
        restored.update(1_000_000_000_000L);assertEquals(6,restored.remainingSeconds());assertEquals(saved.next(),restored.next());
    }
    @Test void recoveryRetainsFinalZone(){var live=new ZoneRuntime(new Zone(0,0,500),profile(),new Random(1),0);live.update(100_000_000_000L);var restored=ZoneRuntime.restore(live.snapshot(),profile(),new Random(2),0);assertEquals(ZonePhase.FINAL,restored.phase());assertEquals(live.current(),restored.current());assertNull(restored.next());}
    @Test void outOfCoverageRejected() {
        assertThrows(IllegalArgumentException.class,()->profile().initialHalfSize(0));
        assertThrows(IllegalArgumentException.class,()->profile().initialHalfSize(33));
    }
    @Test void initialRandomIsReproducibleAndContained() {
        var area=new PlayableArea(-2345,4567,-3000,1000);
        Random a=new Random(10),b=new Random(10);
        for(int i=0;i<2000;i++) {
            Zone zone=ZoneGeometry.initial(area,1000,a);
            assertEquals(zone,ZoneGeometry.initial(area,1000,b)); assertTrue(area.contains(zone));
        }
    }
    @Test void exactFitAndImpossibleArea() {
        assertEquals(new Zone(500,100,500),ZoneGeometry.initial(new PlayableArea(0,1000,-400,600),500,new Random()));
        assertThrows(IllegalArgumentException.class,()->ZoneGeometry.initial(new PlayableArea(0,999,0,1000),500,new Random()));
    }
    @Test void nextZoneAlwaysContainedWithOffsetAtLimits() {
        Zone parent=new Zone(123,-45,500);
        for(int i=0;i<3000;i++) {
            Zone next=ZoneGeometry.next(parent,125,new Random(i));
            assertTrue(next.minX()>=parent.minX() && next.maxX()<=parent.maxX());
            assertTrue(next.minZ()>=parent.minZ() && next.maxZ()<=parent.maxZ());
        }
        Random low=new Random() { @Override public double nextDouble() { return 0; } };
        assertEquals(parent.minX(),ZoneGeometry.next(parent,125,low).minX());
        assertThrows(IllegalArgumentException.class,()->ZoneGeometry.next(parent,500,low));
        assertThrows(IllegalArgumentException.class,()->ZoneGeometry.next(parent,0,low));
    }
    @ParameterizedTest @CsvSource({"-1,0","0,0",".5,.5","1,1","2,1"})
    void interpolationClamps(double input,double progress) {
        Zone a=new Zone(0,100,500),b=new Zone(50,0,250),r=ZoneGeometry.interpolate(a,b,input);
        assertEquals(50*progress,r.centerX()); assertEquals(100-100*progress,r.centerZ());
        assertEquals(500-250*progress,r.halfSize());
        if(progress==0) assertSame(a,r); if(progress==1) assertSame(b,r);
    }
    static final class FakeClock implements GameClock {
        long value=1000;
        public long nanoTime() { return value; }
        void seconds(long seconds) { value+=seconds*1_000_000_000L; }
    }
    @Test void elapsedTimeDrivesWaitShrinkNextAndFinal() {
        var clock=new FakeClock(); Zone initial=new Zone(0,0,500);
        var runtime=new ZoneRuntime(initial,profile(),new Random(3),clock.nanoTime());
        Zone firstTarget=runtime.next();
        assertEquals(ZonePhase.WAITING,runtime.phase()); assertEquals(5,runtime.remainingSeconds());
        clock.seconds(5); runtime.update(clock.nanoTime()); assertEquals(ZonePhase.SHRINKING,runtime.phase());
        clock.seconds(5); runtime.update(clock.nanoTime()); assertEquals(450,runtime.current().halfSize()); assertEquals(.5,runtime.progress());
        clock.seconds(5); runtime.update(clock.nanoTime()); assertEquals(ZonePhase.WAITING,runtime.phase());
        assertSame(firstTarget,runtime.current()); assertEquals(1,runtime.stageIndex()); assertEquals(250,runtime.next().halfSize());
        clock.seconds(500); runtime.update(clock.nanoTime()); assertEquals(ZonePhase.FINAL,runtime.phase());
        assertEquals(250,runtime.current().halfSize()); assertNull(runtime.next()); assertSame(initial,runtime.initial()); assertEquals(1,runtime.progress());
        assertEquals(profile().stages().getLast(),runtime.stage());
    }
    @Test void zeroWaitStartsShrinkingAndLongJumpMatchesFineTicks() {
        var p=new ZoneProfile("zero",profile().initialSizes(),List.of(stage(0,10,400),stage(0,10,250)));
        var a=new ZoneRuntime(new Zone(0,0,500),p,new Random(9),0);
        var b=new ZoneRuntime(new Zone(0,0,500),p,new Random(9),0);
        assertEquals(ZonePhase.SHRINKING,a.phase());
        for(int i=0;i<=20;i++) a.update(i*1_000_000_000L);
        b.update(20_000_000_000L); assertEquals(a.current(),b.current()); assertEquals(a.phase(),b.phase());
        assertEquals(p.stages().getLast(),b.stage());
    }
    @Test void damageAtEdgesCornersCapsAndTrueHealth() {
        var zone=new Zone(0,0,500); var stage=stage(0,1,400);
        assertEquals(0,ZoneDamage.amount(zone,0,0,stage));
        assertEquals(0,ZoneDamage.amount(zone,500,500,stage));
        assertEquals(1.1,ZoneDamage.amount(zone,510,0,stage));
        assertEquals(1.05,ZoneDamage.amount(zone,503,504,stage));
        assertEquals(6,ZoneDamage.amount(zone,5000,0,stage));
        assertEquals(0,ZoneDamage.healthAfter(3,20,6));
        assertEquals(20,ZoneDamage.healthAfter(25,20,1));
        assertThrows(IllegalArgumentException.class,()->ZoneDamage.healthAfter(20,20,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->ZoneDamage.healthAfter(20,20,-1));
        var zero=new ZoneProfile.Stage(Duration.ZERO,Duration.ofSeconds(1),100,0,0,0);
        assertEquals(0,ZoneDamage.amount(zone,501,0,zero));
    }
    @Test void lagNeverBurstsDamageAndNegativeNanoOriginWorks() {
        var pulse=new DamagePulse(-5_000_000_000L);
        assertFalse(pulse.due(-4_000_000_001L)); assertTrue(pulse.due(-4_000_000_000L));
        assertTrue(pulse.due(100_000_000_000L)); assertFalse(pulse.due(100_000_000_000L));
        assertFalse(pulse.due(100_999_999_999L)); assertTrue(pulse.due(101_000_000_000L));
    }
}

