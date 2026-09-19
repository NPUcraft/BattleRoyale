package com.lastsector.session;
import com.lastsector.room.*;
import com.lastsector.map.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RegistryTest {
    private RoomDefinition room(String id) {
        return new RoomDefinition(id, id, 1, 20, 1, Duration.ZERO, List.of("city"), "default", "default", true);
    }
    @Test void roomAndMapRegistriesRejectDuplicatesAndProtectCollections() {
        var rooms = new RoomManager(); var room = room("solo"); rooms.register(room);
        assertSame(room, rooms.find("solo").orElseThrow()); assertTrue(rooms.find("unknown").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> rooms.register(room));
        assertThrows(UnsupportedOperationException.class, () -> rooms.all().clear());
        var maps = new MapRegistry();
        var map = new MapTemplate("city", "City", Path.of("maps/city"), new PlayableArea(-1, 1, -1, 1));
        maps.register(map); assertSame(map, maps.find("city").orElseThrow()); assertTrue(maps.find("unknown").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> maps.register(map));
        assertThrows(UnsupportedOperationException.class, () -> maps.all().clear());
    }
    @Test void oneSessionPerRoomAndUniqueIds() {
        var sessions = new SessionManager();
        var first = GameSession.waiting(UUID.randomUUID(), room("solo"), Instant.now());
        sessions.register(first);
        assertThrows(IllegalArgumentException.class, () -> sessions.register(GameSession.waiting(UUID.randomUUID(), room("solo"), Instant.now())));
        assertThrows(IllegalArgumentException.class, () -> sessions.register(GameSession.waiting(first.sessionId(), room("squad"), Instant.now())));
        assertEquals(1, sessions.all().size()); assertSame(first, sessions.findByRoom("solo").orElseThrow());
        assertEquals(GameState.WAITING, first.state());
        assertTrue(first.selectedMap().isEmpty()); assertTrue(first.gameWorld().isEmpty());
        assertTrue(first.players().isEmpty()); assertTrue(first.teams().isEmpty()); assertTrue(first.spectators().isEmpty());
        sessions.register(GameSession.waiting(UUID.randomUUID(), room("squad"), Instant.now()));
        assertEquals(2, sessions.all().size());
    }
}

