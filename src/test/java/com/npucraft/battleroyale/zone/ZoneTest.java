package com.npucraft.battleroyale.zone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class ZoneTest {
    private final Zone zone = new Zone(10, -20, 5);
    @ParameterizedTest
    @CsvSource({"10,-20", "5,-20", "15,-20", "10,-25", "10,-15", "5,-25", "15,-15"})
    void containsCenterAndEdges(double x, double z) { assertTrue(zone.contains(x, z)); assertEquals(0, zone.distanceOutside(x, z)); }
    @ParameterizedTest
    @CsvSource({"18,-20,3", "2,-20,3", "10,-29,4", "10,-11,4", "18,-11,5", "2,-29,5", "18,-29,5", "2,-11,5"})
    void outsideSidesAndCorners(double x, double z, double distance) {
        assertFalse(zone.contains(x, z)); assertEquals(distance, zone.distanceOutside(x, z), 1e-10);
    }
    @ParameterizedTest @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidHalfSize(double value) { assertThrows(IllegalArgumentException.class, () -> new Zone(0, 0, value)); }
    @Test void rejectsInvalidCentersAndCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new Zone(Double.NaN, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new Zone(0, Double.POSITIVE_INFINITY, 5));
        assertThrows(IllegalArgumentException.class, () -> new Zone(Double.MAX_VALUE, 0, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> zone.contains(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> zone.distanceOutside(0, Double.NEGATIVE_INFINITY));
    }
    @Test void zeroSizeIsAnEmptyZoneEvenAtItsCenter() {
        var empty=new Zone(10,-20,0);assertFalse(empty.contains(10,-20));assertFalse(empty.contains(10.01,-20));
        assertEquals(0,empty.distanceOutside(10,-20));assertEquals(5,empty.distanceOutside(13,-16));
        assertEquals(empty.minX(),empty.maxX());assertEquals(empty.minZ(),empty.maxZ());
    }
    @Test void bounds() { assertEquals(5, zone.minX()); assertEquals(15, zone.maxX()); assertEquals(-25, zone.minZ()); assertEquals(-15, zone.maxZ()); }
}

