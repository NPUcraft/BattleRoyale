package com.npucraft.battleroyale.service;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.config.ConfigurationSnapshot;
import com.npucraft.battleroyale.config.PluginSettings;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.Zone;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomPreparationAreaTest {
    private RoomRuntimeService runtime(WorldProvider worlds, MatchLifecycle matches) {
        var config = new ConfigurationSnapshot(new PluginSettings(false,"sqlite","none",Path.of("runtime")),
                List.of(TestSupport.room("a",1,8)), List.of(TestSupport.map(Path.of("city"))), List.of());
        return new RoomRuntimeService(() -> config, new SessionManager(), new TestSupport.Scheduler(),
                MapSelector.random(new Random(1)), worlds, new TestSupport.Players(), Clock.systemUTC(), matches);
    }
    @Test void copyWaitsForDurablePreparationAndReceivesTheSameFrozenZone() {
        Zone initial = new Zone(-25,30,50);
        var barrier = new CompletableFuture<Void>();
        class Worlds implements WorldProvider {
            Optional<Zone> copiedArea;
            int requests;
            public CompletionStage<GameWorld> prepare(UUID id, String room, MapTemplate map, BooleanSupplier current) {
                throw new AssertionError("Scoped overload was not used");
            }
            public CompletionStage<GameWorld> prepare(UUID id, String room, MapTemplate map, Optional<Zone> area, BooleanSupplier current) {
                assertTrue(barrier.isDone()); assertTrue(current.getAsBoolean());
                copiedArea = area; requests++; return new CompletableFuture<>();
            }
            public CompletionStage<Void> release(GameWorld world) { return CompletableFuture.completedFuture(null); }
            public boolean busy() { return false; }
            public void close() {}
        }
        var worlds = new Worlds();
        var matches = new MatchLifecycle() {
            public CompletionStage<Void> prepareDurably(GameSession session) {
                assertEquals(GameState.PREPARING, session.state()); session.initialZone(initial); return barrier;
            }
            public void start(GameSession session, Runnable ready, Consumer<Throwable> failed) { ready.run(); }
            public void running(GameSession session, Consumer<Throwable> failed) {}
            public void stop(GameSession session, Runnable drained) { drained.run(); }
            public void disconnected(UUID player) {}
            public void close() {}
        };
        var runtime = runtime(worlds, matches);
        runtime.join(UUID.randomUUID(), "a"); runtime.debugStart("a");
        assertEquals(0, worlds.requests);
        barrier.complete(null);
        assertEquals(1, worlds.requests); assertSame(initial, worlds.copiedArea.orElseThrow());
        assertSame(initial, runtime.session("a").orElseThrow().initialZone().orElseThrow());
        runtime.close();
    }
    @Test void lifecycleWithoutAreaContinuesToUseTheLegacyWorldProvider() {
        var worlds = new TestSupport.Worlds();
        var runtime = runtime(worlds, new TestSupport.Players().matches());
        runtime.join(UUID.randomUUID(), "a"); runtime.debugStart("a");
        var session = runtime.session("a").orElseThrow();
        assertTrue(session.initialZone().isEmpty()); assertTrue(worlds.pending.containsKey(session.sessionId()));
        worlds.succeed(session.sessionId());
        assertEquals(GameState.RUNNING, session.state()); runtime.close();
    }
}
