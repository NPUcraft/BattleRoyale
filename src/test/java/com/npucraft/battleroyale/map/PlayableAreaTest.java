package com.npucraft.battleroyale.map;
import com.npucraft.battleroyale.zone.Zone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlayableAreaTest {
    @Test void validDimensionsAndPoints() {
        var area = new PlayableArea(-10, 20, -30, 40);
        assertTrue(area.contains(0, 0)); assertTrue(area.contains(-10, 40)); assertFalse(area.contains(21, 0));
        assertFalse(area.contains(0, -31));
    }
    @Test void invalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PlayableArea(1, 1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlayableArea(2, 1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlayableArea(0, 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlayableArea(0, 1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlayableArea(0, Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlayableArea(0, 1, Double.NEGATIVE_INFINITY, 1));
    }
    @Test void containsWholeZoneIncludingEdges() {
        var area = new PlayableArea(-10, 10, -20, 20);
        assertTrue(area.contains(new Zone(0, 0, 10)));
        assertFalse(area.contains(new Zone(1, 0, 10)));
        assertFalse(area.contains(new Zone(0, 11, 10)));
        assertFalse(area.contains(new Zone(0, 0, 21)));
    }
}

