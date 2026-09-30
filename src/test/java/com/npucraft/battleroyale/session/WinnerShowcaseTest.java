package com.npucraft.battleroyale.session;
import com.npucraft.battleroyale.TestSupport;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    @Test void countdownShowsCeilingImmediatelyAndOnlyChangesOncePerSecond() {
        List<Integer> shown=new ArrayList<>();
        var showcase=new WinnerShowcase(scheduler,()->now,Duration.ofSeconds(60),()->titles++,()->effects++,shown::add,()->complete++,error->errors++);
        assertEquals(List.of(60),shown);assertEquals(60,showcase.remainingSeconds());
        now=999_999_999L;scheduler.ticks(30);assertEquals(List.of(60),shown);
        now=1_000_000_000L;scheduler.ticks(20);assertEquals(List.of(60,59),shown);
        now=59_999_999_999L;scheduler.ticks(1);assertEquals(List.of(60,59,1),shown);
        now=60_000_000_000L;scheduler.ticks(5);assertEquals(List.of(60,59,1,0),shown);assertEquals(1,complete);
        assertEquals(0,scheduler.active());assertEquals(0,errors);
    }
    @Test void recoveredFractionalDeadlineDoesNotRestartFullShowcase() {
        List<Integer> shown=new ArrayList<>();now=100_000_000_000L;
        new WinnerShowcase(scheduler,()->now,Duration.ofMillis(1250),()->{},()->{},shown::add,()->complete++,error->errors++);
        assertEquals(List.of(2),shown);now+=250_000_000L;scheduler.ticks(1);assertEquals(List.of(2,1),shown);
        now+=1_000_000_000L;scheduler.ticks(1);assertEquals(List.of(2,1,0),shown);assertEquals(1,complete);
    }
    @Test void countdownFailureCancelsOwnedTaskAndDoesNotRepeatOrComplete() {
        List<Integer> shown=new ArrayList<>();
        new WinnerShowcase(scheduler,()->now,Duration.ofSeconds(60),()->{},()->{},seconds->{shown.add(seconds);if(seconds<60)throw new IllegalStateException("display failed");},()->complete++,error->errors++);
        now=1_000_000_000L;scheduler.ticks(5);assertEquals(List.of(60,59),shown);assertEquals(1,errors);assertEquals(0,complete);assertEquals(0,scheduler.active());
    }
    @Test void cancellationStopsFurtherCountdownUpdates() {
        List<Integer> shown=new ArrayList<>();
        var showcase=new WinnerShowcase(scheduler,()->now,Duration.ofSeconds(60),()->{},()->{},shown::add,()->complete++,error->errors++);
        showcase.close();now=60_000_000_000L;scheduler.ticks(5);assertEquals(List.of(60),shown);assertEquals(0,complete);
    }
}
