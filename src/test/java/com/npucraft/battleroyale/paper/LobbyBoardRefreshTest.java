package com.npucraft.battleroyale.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyBoardRefreshTest {
    @Test void unavailableAndThirtySecondGateNeverPollDatabaseEveryTick(){
        var clock=new AtomicLong();var calls=new AtomicInteger();var accepted=new ArrayList<Integer>();
        var refresh=new LobbyBoardRefresh<>(30_000,clock::get,()->CompletableFuture.completedFuture(calls.incrementAndGet()),Runnable::run,accepted::add,error->fail(error));
        refresh.tick(false);assertEquals(0,calls.get());
        for(int i=0;i<600;i++){clock.set(i*50);refresh.tick(true);}
        assertEquals(List.of(1),accepted);clock.set(30_000);refresh.tick(true);assertEquals(List.of(1,2),accepted);
    }
    @Test void oneInflightQueryAndCompletionOnlyThroughOwnerDispatcher(){
        var clock=new AtomicLong();var calls=new AtomicInteger();var future=new CompletableFuture<String>();var dispatch=new ArrayList<Runnable>();var accepted=new ArrayList<String>();
        var refresh=new LobbyBoardRefresh<>(30,clock::get,()->{calls.incrementAndGet();return future;},dispatch::add,accepted::add,error->fail(error));
        refresh.tick(true);clock.set(300);refresh.tick(true);assertEquals(1,calls.get());
        future.complete("rows");assertTrue(accepted.isEmpty());assertTrue(refresh.inFlight());
        dispatch.removeFirst().run();assertEquals(List.of("rows"),accepted);assertFalse(refresh.inFlight());
    }
    @Test void shutdownIgnoresAlreadyQueuedAndLateCompletions(){
        var future=new CompletableFuture<String>();var dispatch=new ArrayList<Runnable>();var accepted=new ArrayList<String>();
        var refresh=new LobbyBoardRefresh<>(30,()->0,future::minimalCompletionStage,dispatch::add,accepted::add,error->fail(error));
        refresh.tick(true);future.complete("rows");refresh.close();dispatch.removeFirst().run();refresh.tick(true);
        assertTrue(accepted.isEmpty());assertFalse(future.isCancelled());
    }
    @Test void failedQueriesRetainThrottleAndCanRecover(){
        var clock=new AtomicLong();var calls=new AtomicInteger();var errors=new ArrayList<Throwable>();var accepted=new ArrayList<String>();
        var refresh=new LobbyBoardRefresh<>(30,clock::get,()->calls.incrementAndGet()==1?CompletableFuture.failedFuture(new IllegalStateException("offline")):CompletableFuture.completedFuture("real rows"),Runnable::run,accepted::add,errors::add);
        refresh.tick(true);refresh.tick(true);assertEquals(1,calls.get());assertEquals(1,errors.size());assertTrue(accepted.isEmpty());
        clock.set(30);refresh.tick(true);assertEquals(List.of("real rows"),accepted);
    }
    @Test void immediateSourceAndPresentationFailuresReachErrorSink(){
        var errors=new ArrayList<Throwable>();var source=new LobbyBoardRefresh<String>(30,()->0,()->{throw new IllegalStateException("source");},Runnable::run,value->{},errors::add);
        source.tick(true);assertFalse(source.inFlight());assertEquals("source",errors.getFirst().getMessage());
        var presentation=new LobbyBoardRefresh<>(30,()->0,()->CompletableFuture.completedFuture("rows"),Runnable::run,value->{throw new IllegalStateException("display");},errors::add);
        presentation.tick(true);assertFalse(presentation.inFlight());assertEquals("display",errors.getLast().getMessage());
    }
}
