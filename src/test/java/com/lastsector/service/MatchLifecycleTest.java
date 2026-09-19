package com.lastsector.service;
import com.lastsector.TestSupport;
import com.lastsector.config.*;
import com.lastsector.map.MapSelector;
import com.lastsector.session.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MatchLifecycleTest {
    TestSupport.Scheduler scheduler=new TestSupport.Scheduler();
    TestSupport.Worlds worlds=new TestSupport.Worlds();
    TestSupport.Players players=new TestSupport.Players();
    static class Matches implements MatchLifecycle {
        Runnable ready,drained;
        Consumer<Throwable> failed,runtimeFailed;
        boolean failInitial,closed;
        int running;
        public void start(GameSession s,Runnable ready,Consumer<Throwable> failed) {
            if(failInitial) throw new IllegalArgumentException("initial zone");
            this.ready=ready; this.failed=failed;
        }
        public void running(GameSession s,Consumer<Throwable> failed) { running++; runtimeFailed=failed; }
        public void stop(GameSession s,Runnable drained) { this.drained=drained; }
        public void disconnected(UUID player) {}
        public void close() { closed=true; }
    }
    Matches matches=new Matches();
    RoomRuntimeService runtime;
    GameSession start() {
        var config=new ConfigurationSnapshot(new PluginSettings(false,"sqlite","none",Path.of("runtime")),
                List.of(TestSupport.room("a",1,8)),List.of(TestSupport.map(Path.of("city"))),List.of());
        runtime=new RoomRuntimeService(()->config,new SessionManager(),scheduler,MapSelector.random(new Random(1)),
                worlds,players,Clock.systemUTC(),matches);
        runtime.join(UUID.randomUUID(),"a"); runtime.debugStart("a");
        var session=runtime.session("a").orElseThrow(); worlds.succeed(session.sessionId()); return session;
    }
    @Test void startingWaitsForCompletePlanAndDrainBeforeWorldUnload() {
        var session=start(); assertEquals(GameState.STARTING,session.state()); assertEquals(0,matches.running);
        runtime.debugEnd("a"); assertEquals(GameState.CLEANUP,session.state()); assertTrue(worlds.released.isEmpty());
        matches.ready.run(); assertEquals(0,matches.running);
        matches.drained.run(); assertEquals(1,worlds.released.size()); assertTrue(runtime.session("a").isEmpty());
    }
    @Test void initialZoneFailureUsesSameRollback() {
        matches.failInitial=true; var session=start(); assertEquals(GameState.CLEANUP,session.state());
        matches.drained.run(); assertTrue(runtime.session("a").isEmpty()); assertTrue(players.errors>0);
    }
    @Test void asynchronousSpawnFailureUsesSameRollback() {
        var session=start(); matches.failed.accept(new IllegalStateException("spawn/chunk"));
        assertEquals(GameState.CLEANUP,session.state()); matches.drained.run();
        assertTrue(runtime.canReload()); assertEquals(0,matches.running);
    }
    @Test void runtimeFailureStopsAndCleansSession() {
        var session=start(); matches.ready.run(); assertEquals(GameState.RUNNING,session.state());
        matches.runtimeFailed.accept(new IllegalStateException("zone tick"));
        assertEquals(GameState.CLEANUP,session.state()); matches.drained.run(); assertTrue(runtime.canReload());
    }
    @Test void disableRejectsLateReadiness() {
        start(); runtime.close(); matches.ready.run();
        assertTrue(matches.closed); assertTrue(worlds.closed); assertEquals(0,matches.running);
    }
    @Test void loopFailureCancelsOnlyItsOwnTaskAndNeverRepeats() {
        int[] first={0},second={0},errors={0};
        var a=new SessionLoop(scheduler,()-> { first[0]++; throw new IllegalStateException("tick"); },error->errors[0]++);
        var b=new SessionLoop(scheduler,()->second[0]++,error->fail(error));
        scheduler.ticks(5); assertEquals(1,first[0]); assertEquals(1,errors[0]); assertEquals(5,second[0]); assertEquals(1,scheduler.active());
        a.close(); b.close(); scheduler.ticks(5); assertEquals(5,second[0]); assertEquals(0,scheduler.active());
    }
}

