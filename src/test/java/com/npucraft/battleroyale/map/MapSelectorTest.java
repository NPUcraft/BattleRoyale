package com.npucraft.battleroyale.map;
import com.npucraft.battleroyale.TestSupport;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MapSelectorTest {
    @Test void singleMapPoolNeverSelectsOutsideMap() {
        var map = TestSupport.map(Path.of("city"));
        var other = new MapTemplate("desert", "Desert", Path.of("desert"), map.playableArea());
        var selector = MapSelector.random(new Random(23));
        for (int i = 0; i < 20; i++) assertSame(map, selector.select(TestSupport.room("a", 1, 2), List.of(other, map)));
    }
    @Test void seededSelectorIsRepeatable() {
        var room = new com.npucraft.battleroyale.room.RoomDefinition("r", "R", 1, 4, 1, java.time.Duration.ZERO,
                List.of("city", "desert"), "default", "default", true);
        var city = TestSupport.map(Path.of("city"));
        var maps = List.of(city, new MapTemplate("desert", "Desert", Path.of("desert"), city.playableArea()));
        var first = MapSelector.random(new Random(77)); var second = MapSelector.random(new Random(77));
        for (int i = 0; i < 20; i++) assertEquals(first.select(room, maps), second.select(room, maps));
    }
}

