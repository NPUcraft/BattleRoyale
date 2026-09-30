package com.npucraft.battleroyale.combat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
class ExperienceMathTest {
    @ParameterizedTest @CsvSource({"0,0","1,0","2,1","100,50","101,50","2147483647,1073741823"})
    void exactHalf(int total,int expected) {assertEquals(expected,ExperienceMath.stored(total));}
    @ParameterizedTest @CsvSource({"0,0","1,7","16,352","17,394","30,1395","31,1507","32,1628"})
    void levelBoundariesUseExactMinecraftXpCurve(int level,int total) {assertEquals(total,ExperienceMath.total(level,0));}
    @Test void progressIsWholeEarnedPointsAndClampedAgainstOverflow() {
        assertEquals(101,ExperienceMath.total(7,10f/21));assertEquals(Integer.MAX_VALUE,ExperienceMath.total(Integer.MAX_VALUE,.5f));
        assertThrows(IllegalArgumentException.class,()->ExperienceMath.total(-1,0));assertThrows(IllegalArgumentException.class,()->ExperienceMath.stored(-1));
    }
    @Test void invalidBottleNumbersRejected() {assertFalse(ExperienceMath.validBottle(null));assertFalse(ExperienceMath.validBottle(-1));assertFalse(ExperienceMath.validBottle(0));assertFalse(ExperienceMath.validBottle(Integer.MAX_VALUE));assertTrue(ExperienceMath.validBottle(347));}
}
