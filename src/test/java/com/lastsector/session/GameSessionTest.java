package com.lastsector.session;
import com.lastsector.TestSupport;
import com.lastsector.map.GameWorld;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GameSessionTest {
    @Test void initialZoneIsAssignedOnceAndCannotBeChangedByRuntime() {
        var session=session(); var initial=new com.lastsector.zone.Zone(0,0,500);
        assertThrows(IllegalStateException.class,()->session.initialZone(initial));
        session.join(UUID.randomUUID()); var map=TestSupport.map(Path.of("city")); session.prepare(map);
        session.starting(new GameWorld(session.sessionId(),"a","runtime",Path.of("runtime"),map));
        session.initialZone(initial);
        assertThrows(IllegalStateException.class,()->session.initialZone(new com.lastsector.zone.Zone(1,1,500)));
        session.transition(GameState.RUNNING); session.transition(GameState.ENDING); session.transition(GameState.CLEANUP);
        assertSame(initial,session.initialZone().orElseThrow());
    }
    GameSession session() { return GameSession.waiting(UUID.randomUUID(), TestSupport.room("a", 1, 2), Instant.now()); }
    @Test void legalCountdownCancellationAndNormalLifecycle() {
        var session = session(); session.join(UUID.randomUUID());
        session.transition(GameState.COUNTDOWN); session.transition(GameState.WAITING);
        session.transition(GameState.COUNTDOWN);
        var map = TestSupport.map(Path.of("city")); session.prepare(map);
        session.starting(new GameWorld(session.sessionId(), "a", "runtime", Path.of("runtime"), map));
        session.transition(GameState.RUNNING); session.transition(GameState.ENDING); session.transition(GameState.CLEANUP);
        assertThrows(IllegalStateException.class, () -> session.transition(GameState.WAITING));
    }
    @Test void invalidTransitionsCannotSkipResourceRequirements() {
        var session = session();
        assertThrows(IllegalStateException.class, () -> session.transition(GameState.RUNNING));
        assertThrows(IllegalStateException.class, () -> session.transition(GameState.PREPARING));
        assertThrows(IllegalStateException.class, () -> session.transition(GameState.ENDING));
        assertThrows(IllegalStateException.class, () -> session.prepare(TestSupport.map(Path.of("city"))));
    }
    @Test void wrongWorldSessionAndRoomOwnershipIsRejected() {
        var session = session(); session.join(UUID.randomUUID());
        var map = TestSupport.map(Path.of("city")); session.prepare(map);
        assertThrows(IllegalStateException.class, () -> session.starting(new GameWorld(UUID.randomUUID(), "a", "runtime", Path.of("runtime"), map)));
        assertThrows(IllegalStateException.class, () -> session.starting(new GameWorld(session.sessionId(), "other", "runtime", Path.of("runtime"), map)));
        assertEquals(GameState.PREPARING, session.state());
    }
    @Test void playersAreImmutableSnapshotsAndRunningKeepsDisconnectedIdentity() {
        var session = session(); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        session.join(first); var before = session.players(); session.join(second);
        assertEquals(1, before.size()); assertThrows(UnsupportedOperationException.class, () -> before.clear());
        var map = TestSupport.map(Path.of("city")); session.prepare(map); session.disconnected(first);
        session.starting(new GameWorld(session.sessionId(), "a", "runtime", Path.of("runtime"), map)); session.transition(GameState.RUNNING);
        assertEquals(com.lastsector.player.PlayerState.DISCONNECTED, session.players().get(first).state());
        assertEquals(com.lastsector.player.PlayerState.ALIVE, session.players().get(second).state());
    }
}

