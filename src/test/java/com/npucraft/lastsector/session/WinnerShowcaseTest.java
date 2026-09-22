package com.npucraft.lastsector.session;
import com.npucraft.lastsector.TestSupport;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class WinnerShowcaseTest {
    TestSupport.Scheduler scheduler=new TestSupport.Scheduler();long now;int titles,effects,complete,errors;
    WinnerShowcase start() {return new WinnerShowcase(scheduler,()->now,Duration.ofSeconds(60),()->titles++,()->effects++,()->complete++,error->errors++);}
    @Test void fullMinuteByMonotonicClockThenExactlyOneCleanup() {
        start();assertEquals(1,titles);assertEquals(1,effects);scheduler.ticks(1200);assertEquals(0,complete);
        now=59_999_999_999L;scheduler.ticks(1);assertEquals(0,complete);now=60_000_000_000L;scheduler.ticks(5);assertEquals(1,complete);assertEquals(0,scheduler.active());
    }
    @Test void debugEndCancellationPreventsLateCleanupAndEffects() {
        var showcase=start();showcase.close();showcase.close();now=100_000_000_000L;scheduler.ticks(5);assertEquals(0,complete);assertEquals(1,effects);assertEquals(0,scheduler.active());
    }
    @Test void effectsAreBoundedNotEveryTick() {start();for(int i=0;i<1200;i++){now+=50_000_000;scheduler.ticks(1);}assertEquals(12,effects);assertEquals(1,complete);}
    @Test void effectFailureCancelsOwnedTaskAndReportsOnce() {
        new WinnerShowcase(scheduler,()->now,Duration.ofSeconds(60),()->{},()->{if(++effects>1)throw new IllegalStateException();},()->complete++,error->errors++);
        now=5_000_000_000L;scheduler.ticks(2);assertEquals(1,errors);assertEquals(0,scheduler.active());assertEquals(0,complete);
    }
}
