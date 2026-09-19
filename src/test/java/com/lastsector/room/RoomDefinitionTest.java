package com.lastsector.room;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoomDefinitionTest {
    private RoomDefinition room(int min, int max, int team, List<String> maps) {
        return new RoomDefinition("solo", "Solo", min, max, team, Duration.ofSeconds(60), maps, "default", "default", true);
    }
    @Test void validatesMinimum() { assertThrows(IllegalArgumentException.class, () -> room(0, 24, 1, List.of("city"))); }
    @Test void validatesMaximum() { assertThrows(IllegalArgumentException.class, () -> room(4, 2, 1, List.of("city"))); }
    @Test void validatesTeamSize() { assertThrows(IllegalArgumentException.class, () -> room(1, 24, 0, List.of("city"))); }
    @Test void validatesMaps() { assertThrows(IllegalArgumentException.class, () -> room(1, 24, 1, List.of())); }
    @Test void supportsArbitraryTeamSizeAndCopiesMaps() {
        var maps = new ArrayList<>(List.of("city"));
        var room = room(1, 24, 3, maps); maps.clear();
        assertEquals(3, room.teamSize()); assertEquals(List.of("city"), room.mapPool());
        assertThrows(UnsupportedOperationException.class, () -> room.mapPool().clear());
    }
}

