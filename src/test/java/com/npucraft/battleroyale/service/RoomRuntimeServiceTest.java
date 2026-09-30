package com.npucraft.battleroyale.service;
import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.session.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class RoomRuntimeServiceTest {
    TestSupport.Scheduler clock;
    TestSupport.Worlds worlds;
    TestSupport.Players players;
    RoomRuntimeService runtime;
    UUID alice, bob, carol;
    List<RoomDefinition> definitions;
    @BeforeEach void setup() {
        clock = new TestSupport.Scheduler(); worlds = new TestSupport.Worlds(); players = new TestSupport.Players();
        definitions = List.of(TestSupport.room("a", 2, 3), TestSupport.room("b", 2, 3));
        var snapshot = new ConfigurationSnapshot(new PluginSettings(false, "sqlite", "auto", Path.of("runtime")),
                definitions, List.of(TestSupport.map(Path.of("city"))), List.of());
        runtime = new RoomRuntimeService(() -> snapshot, new SessionManager(), clock,
                MapSelector.random(new Random(7)), worlds, players, Clock.systemUTC(), players.matches());
        alice = UUID.randomUUID(); bob = UUID.randomUUID(); carol = UUID.randomUUID();
    }
    GameSession session(String room) { return runtime.session(room).orElseThrow(); }
    @Test void joinValidAndLeaveWaiting() {
        runtime.join(alice, "a"); assertEquals(GameState.WAITING, session("a").state());
        assertTrue(session("a").players().containsKey(alice)); assertEquals(0, clock.active());
        runtime.leave(alice); assertTrue(runtime.session("a").isEmpty()); assertTrue(runtime.canReload());
    }
    @Test void rejectsUnknownRoom() { assertThrows(IllegalArgumentException.class, () -> runtime.join(alice, "missing")); }
    @Test void rejectsDuplicateAndSecondRoom() {
        runtime.join(alice, "a");
        assertThrows(IllegalStateException.class, () -> runtime.join(alice, "a"));
        assertThrows(IllegalStateException.class, () -> runtime.join(alice, "b"));
        assertEquals(1, session("a").players().size()); assertTrue(runtime.session("b").isEmpty());
    }
    @Test void fullRoomDoesNotShortenCountdown() {
        runtime.join(alice, "a"); runtime.join(bob, "a"); clock.seconds(1); runtime.join(carol, "a");
        assertEquals(2, runtime.remaining(session("a")));
        assertThrows(IllegalStateException.class, () -> runtime.join(UUID.randomUUID(), "a"));
    }
    @Test void countdownStartsAtMinimumAndCancelsAndRestartsFromFullDuration() {
        runtime.join(alice, "a"); runtime.join(bob, "a");
        assertEquals(GameState.COUNTDOWN, session("a").state()); clock.seconds(2);
        assertEquals(1, runtime.remaining(session("a")));
        runtime.leave(bob); assertEquals(GameState.WAITING, session("a").state()); assertEquals(0, clock.active());
        clock.seconds(10); assertTrue(worlds.pending.isEmpty());
        runtime.join(bob, "a"); assertEquals(3, runtime.remaining(session("a"))); clock.seconds(3);
        assertEquals(GameState.PREPARING, session("a").state()); assertEquals(0, clock.active());
    }
    @Test void countdownsAreIndependent() {
        runtime.join(alice, "a"); runtime.join(bob, "a");
        runtime.join(carol, "b"); runtime.join(UUID.randomUUID(), "b");
        runtime.leave(bob); clock.seconds(3);
        assertEquals(GameState.WAITING, session("a").state()); assertEquals(GameState.PREPARING, session("b").state());
    }
    @Test void autojoinChoosesMostPopulatedEligible() {
        runtime.join(alice, "b"); assertEquals("b", runtime.autojoin(bob));
    }
    @Test void autojoinTieUsesConfigurationOrder() {
        assertEquals("a", runtime.autojoin(alice)); runtime.join(bob, "b"); assertEquals("a", runtime.autojoin(carol));
    }
    @Test void autojoinIgnoresFullRoom() {
        runtime.join(alice, "a"); runtime.join(bob, "a"); runtime.join(carol, "a");
        assertEquals("b", runtime.autojoin(UUID.randomUUID()));
    }
    @Test void autojoinIgnoresPreparingAndFailsWhenNoneAvailable() {
        runtime.join(alice, "a"); runtime.debugStart("a");
        assertEquals("b", runtime.autojoin(bob)); runtime.debugStart("b");
        assertThrows(IllegalStateException.class, () -> runtime.autojoin(carol));
    }
    @Test void noJoinOrLeaveWhilePreparingOrRunning() {
        runtime.join(alice, "a"); runtime.debugStart("a");
        assertThrows(IllegalStateException.class, () -> runtime.join(bob, "a"));
        assertThrows(IllegalStateException.class, () -> runtime.leave(alice));
        worlds.succeed(session("a").sessionId());
        assertThrows(IllegalStateException.class, () -> runtime.leave(alice));
        assertThrows(IllegalStateException.class, () -> runtime.debugStart("a"));
    }
    @Test void debugStartRequiresOnePlayer() { assertThrows(IllegalStateException.class, () -> runtime.debugStart("a")); }
    @Test void completeLifecycleReplacesSessionIdentity() {
        runtime.join(alice, "a"); GameSession old = session("a");
        runtime.debugStart("a"); worlds.succeed(old.sessionId());
        assertEquals(GameState.RUNNING, old.state()); assertEquals(1, players.staged);
        runtime.debugEnd("a"); assertEquals(GameState.CLEANUP, old.state());
        assertTrue(runtime.session("a").isEmpty()); assertEquals(1, worlds.released.size());
        runtime.join(alice, "a"); assertNotEquals(old.sessionId(), session("a").sessionId());
    }
    @Test void debugEndSkipsShowcaseWithoutChangingOutcome() {
        runtime.join(alice,"a");runtime.debugStart("a");var current=session("a");worlds.succeed(current.sessionId());
        var result=new MatchOutcome(Set.of(alice),false,"LAST_ALIVE",123,42);current.outcome(result);
        runtime.debugEnd("a");assertTrue(runtime.session("a").isEmpty());assertEquals(1,worlds.released.size());
        assertSame(result,current.outcome().orElseThrow());assertEquals(GameState.CLEANUP,current.state());
    }
    @Test void runningDebugEndDoesNotInventOutcome() {
        runtime.join(alice,"a");runtime.debugStart("a");var current=session("a");worlds.succeed(current.sessionId());
        runtime.debugEnd("a");assertTrue(current.outcome().isEmpty());
    }
    @Test void selectedMapCannotChange() {
        runtime.join(alice, "a"); runtime.debugStart("a"); var current = session("a");
        assertThrows(IllegalStateException.class, () -> current.prepare(TestSupport.map(Path.of("elsewhere"))));
        assertEquals(Path.of("city"), current.selectedMap().orElseThrow().templatePath());
    }
    @Test void copyOrLoadFailureReleasesRoom() {
        runtime.join(alice, "a"); runtime.debugStart("a"); worlds.fail(session("a").sessionId());
        assertTrue(runtime.session("a").isEmpty()); assertTrue(runtime.canReload()); assertTrue(players.errors > 0);
    }
    @Test void cancelDuringCopyInvalidatesGuardAndWaitsForCompletion() {
        runtime.join(alice, "a"); runtime.debugStart("a"); var current = session("a");
        assertTrue(worlds.guards.get(current.sessionId()).getAsBoolean());
        runtime.debugEnd("a"); assertEquals(GameState.CLEANUP, current.state());
        assertFalse(worlds.guards.get(current.sessionId()).getAsBoolean());
        assertThrows(IllegalStateException.class, () -> runtime.join(bob, "a"));
        worlds.succeed(current.sessionId()); // Deliberately misbehaving provider: stale success must still be released.
        assertEquals(0, players.staged); assertTrue(runtime.session("a").isEmpty()); assertEquals(1, worlds.released.size());
    }
    @Test void cancelledCopyFailureAlsoRetiresSession() {
        runtime.join(alice, "a"); runtime.debugStart("a"); var id = session("a").sessionId();
        runtime.debugEnd("a"); worlds.fail(id); assertTrue(runtime.session("a").isEmpty());
    }
    @Test void teleportFailureAbortsAndReleasesWorld() {
        players.stageSuccess = false; runtime.join(alice, "a"); runtime.debugStart("a");
        worlds.succeed(session("a").sessionId()); assertTrue(runtime.session("a").isEmpty()); assertEquals(1, worlds.released.size());
    }
    @Test void cleanupFailureDoesNotBlockNextMatch() {
        runtime.join(alice, "a"); runtime.debugStart("a"); worlds.succeed(session("a").sessionId());
        worlds.releaseFails = true; runtime.debugEnd("a");
        assertTrue(runtime.session("a").isEmpty()); runtime.join(alice, "a"); assertEquals(GameState.WAITING, session("a").state());
    }
    @Test void multiRoomFailureAndCleanupAreIsolated() {
        runtime.join(alice, "a"); runtime.join(bob, "b"); runtime.debugStart("a"); runtime.debugStart("b");
        var a = session("a"); var b = session("b"); assertNotEquals(a.sessionId(), b.sessionId());
        worlds.fail(a.sessionId()); worlds.succeed(b.sessionId());
        assertTrue(runtime.session("a").isEmpty()); assertEquals(GameState.RUNNING, b.state());
        runtime.join(alice, "a"); runtime.debugStart("a"); worlds.succeed(session("a").sessionId()); runtime.debugEnd("a");
        assertEquals(GameState.RUNNING, b.state());
        assertTrue(worlds.released.stream().noneMatch(world -> world.sessionId().equals(b.sessionId())));
    }
    @Test void waitingDisconnectLeavesButActiveDisconnectRetainsUuid() {
        runtime.join(alice, "a"); runtime.join(bob, "a"); runtime.disconnected(bob);
        assertEquals(GameState.WAITING, session("a").state());
        runtime.debugStart("a"); runtime.disconnected(alice);
        assertTrue(session("a").players().containsKey(alice));
        assertEquals(com.npucraft.battleroyale.player.PlayerState.DISCONNECTED, session("a").players().get(alice).state());
    }
    @Test void reloadIsBlockedByMembershipOrProviderResources() {
        assertTrue(runtime.canReload()); runtime.join(alice, "a"); assertFalse(runtime.canReload());
        runtime.leave(alice); worlds.busy = true; assertFalse(runtime.canReload());
    }
    @Test void disableStopsCountdownsAndInvalidatesLateCompletions() {
        runtime.join(alice, "a"); runtime.join(bob, "a"); runtime.join(carol, "b"); runtime.debugStart("b");
        var id = session("b").sessionId(); runtime.close(); clock.seconds(10);
        assertEquals(0, clock.active()); assertTrue(worlds.closed); assertFalse(worlds.guards.get(id).getAsBoolean());
        worlds.succeed(id); assertEquals(0, players.staged); assertThrows(IllegalStateException.class, () -> runtime.join(UUID.randomUUID(), "a"));
    }
}

