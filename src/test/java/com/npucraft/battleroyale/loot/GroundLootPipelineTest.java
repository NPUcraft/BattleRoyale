package com.npucraft.battleroyale.loot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundLootPipelineTest {
    @Test void slowChunkDoesNotHoldOtherReadyCandidatesAndCapacityNeverExpands(){
        var pipeline=new GroundLootPipeline<Integer,String>(2);var slow=new CompletableFuture<String>();
        pipeline.submit(1,()->slow);pipeline.submit(2,()->CompletableFuture.completedFuture("ready"));assertFalse(pipeline.available());
        var requested=new AtomicBoolean();assertThrows(IllegalStateException.class,()->pipeline.submit(3,()->{requested.set(true);return new CompletableFuture<>();}));assertFalse(requested.get());
        var ready=pipeline.takeReady().orElseThrow();assertEquals(2,ready.candidate());assertEquals("ready",ready.future().join());
        assertTrue(pipeline.available());assertTrue(pipeline.takeReady().isEmpty());
        slow.complete("late");assertEquals(1,pipeline.takeReady().orElseThrow().candidate());assertEquals(0,pipeline.size());
    }
    @Test void stopWaitsForEveryActualFutureWithoutCancellingOrExposingCompletedWork(){
        var pipeline=new GroundLootPipeline<Integer,String>(8);var first=new CompletableFuture<String>();var second=new CompletableFuture<String>();
        pipeline.submit(1,()->first);pipeline.submit(2,()->second);var stopped=pipeline.stop();assertSame(stopped,pipeline.stop());
        assertFalse(stopped.isDone());assertFalse(first.isCancelled());assertFalse(second.isCancelled());assertFalse(pipeline.available());
        first.complete("do not inspect");assertFalse(stopped.isDone());assertTrue(pipeline.takeReady().isEmpty());
        second.completeExceptionally(new IllegalStateException("Chunk generation failed"));assertDoesNotThrow(stopped::join);assertTrue(pipeline.takeReady().isEmpty());
        assertThrows(IllegalStateException.class,()->pipeline.submit(3,()->CompletableFuture.completedFuture("late")));
    }
    @Test void failureIsObservableAndOtherOutstandingWorkStillMustDrain(){
        var pipeline=new GroundLootPipeline<Integer,String>(2);var slow=new CompletableFuture<String>();
        pipeline.submit(1,()->CompletableFuture.failedFuture(new IllegalStateException("terrain")));pipeline.submit(2,()->slow);
        assertThrows(CompletionException.class,()->pipeline.takeReady().orElseThrow().future().join());
        var drain=pipeline.stop();assertFalse(drain.isDone());slow.complete("finished");assertTrue(drain.isDone());
    }
    @Test void timeAndOperationBudgetsBoundAHotTickIndependently(){
        var tick=new GroundLootBudget.Tick(100);for(int n=0;n<8;n++){assertTrue(tick.request(100));assertTrue(tick.inspect(100));}
        assertFalse(tick.request(100));assertFalse(tick.inspect(100));
        var slow=new GroundLootBudget.Tick(100);assertTrue(slow.inspect(101));assertFalse(slow.inspect(100+GroundLootBudget.MAX_TICK_NANOS));assertFalse(slow.request(100+GroundLootBudget.MAX_TICK_NANOS));
        assertEquals(8,GroundLootBudget.MAX_IN_FLIGHT);assertEquals(2400,GroundLootBudget.BASE_MAX_ATTEMPTS);
    }
}
