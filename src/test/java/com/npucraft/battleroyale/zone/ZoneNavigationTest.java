package com.npucraft.battleroyale.zone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ZoneNavigationTest {
    @ParameterizedTest @CsvSource({
        "0,10,0,↑", "-10,10,0,↗", "-10,0,0,→", "-10,-10,0,↘",
        "0,-10,0,↓", "10,-10,0,↙", "10,0,0,←", "10,10,0,↖",
        "0,10,90,←", "10,0,270,↑", "0,-10,180,↑", "-10,0,90,↑",
        "0,-10,359,↓", "10,0,359,←", "-10,0,360000090,↑", "-10,0,-270,↑"
    })
    void arrowsFollowBukkitYawIncludingUnwrappedAndNegativeAngles(double dx,double dz,double yaw,String expected) {
        assertEquals(expected,ZoneNavigation.arrow(dx,dz,yaw));
    }
    @Test void outsideCurrentStillMeasuresNextSquareButArrowAlwaysPointsAtItsCenter() {
        var current=new Zone(0,0,100); var next=new Zone(-50,-50,40);
        var hint=ZoneNavigation.guide(current,next,120,30,0);
        assertEquals(ZoneNavigation.Target.ENTER_SAFE_ZONE,hint.target()); assertTrue(hint.outside());
        assertEquals(-50,hint.targetX()); assertEquals(-50,hint.targetZ()); assertEquals(Math.hypot(130,40),hint.distance());
        assertEquals("↘",hint.arrow());assertEquals("→",ZoneNavigation.arrow(-130,-40,0),"The nearest boundary direction intentionally differs");
        assertFalse(hint.arrived());
    }
    @Test void outsideCornersUseEuclideanShortestDistanceWithNoArtificialInset() {
        var current=new Zone(0,0,100); var hint=ZoneNavigation.guide(current,null,103,104,0);
        assertEquals(0,hint.targetX()); assertEquals(0,hint.targetZ()); assertEquals(5,hint.distance(),1e-9);
        var tiny=new Zone(0,0,.5); var entry=ZoneNavigation.guide(tiny,null,2,2,0);
        assertEquals(Math.hypot(1.5,1.5),entry.distance());assertEquals(0,entry.targetX());assertEquals(0,entry.targetZ());
    }
    @Test void safePlayersUseNextCenterAndTheFinalCircleUsesItsOwnCenter() {
        var current=new Zone(0,0,100); var next=new Zone(30,-20,40);
        var nextHint=ZoneNavigation.guide(current,next,10,20,0);
        assertEquals(ZoneNavigation.Target.NEXT_CENTER,nextHint.target()); assertFalse(nextHint.outside());
        assertEquals(30,nextHint.targetX()); assertEquals(-20,nextHint.targetZ()); assertEquals(Math.hypot(20,40),nextHint.distance());
        var finalHint=ZoneNavigation.guide(current,null,10,20,0);
        assertEquals(ZoneNavigation.Target.CURRENT_CENTER,finalHint.target()); assertEquals(0,finalHint.targetX()); assertEquals(0,finalHint.targetZ());
        var outsideNext=ZoneNavigation.guide(current,next,100,0,0);
        assertEquals(ZoneNavigation.Target.ENTER_SAFE_ZONE,outsideNext.target());assertEquals(30,outsideNext.distance());
    }
    @Test void nextSquareEdgesAndCornersCountAsInsideAndMeasureToCenter() {
        var current=new Zone(0,0,100);var next=new Zone(20,-10,30);
        var edge=ZoneNavigation.guide(current,next,50,-10,0);assertFalse(edge.outside());assertEquals(30,edge.distance());
        var corner=ZoneNavigation.guide(current,next,50,20,0);assertFalse(corner.outside());assertEquals(Math.hypot(30,30),corner.distance());
        var justOutside=ZoneNavigation.guide(current,next,50.25,20,0);assertTrue(justOutside.outside());assertEquals(.25,justOutside.distance());
    }
    @Test void arrivalHasNoMisleadingHeadingButOutsideNeverClaimsArrival() {
        var current=new Zone(10,-20,50);
        var center=ZoneNavigation.guide(current,null,10,-20,240);
        assertTrue(center.arrived()); assertEquals("●",center.arrow());
        var near=ZoneNavigation.guide(current,null,10.5,-20,0);assertFalse(near.arrived());assertEquals("→",near.arrow());assertEquals(.5,near.distance());
        assertFalse(ZoneNavigation.guide(new Zone(0,0,.1),null,.11,0,0).arrived());
    }
    @Test void invalidCoordinatesCannotProduceNanInstructions() {
        var current=new Zone(0,0,100);
        assertThrows(IllegalArgumentException.class,()->ZoneNavigation.guide(current,null,Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->ZoneNavigation.guide(current,null,0,0,Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,()->new ZoneNavigation.Point(Double.NaN,0));
        assertThrows(IllegalArgumentException.class,()->new ZoneNavigation.Point(0,Double.NEGATIVE_INFINITY));
        assertEquals(new ZoneNavigation.Point(3,4),new ZoneNavigation.Point(3,4));
    }
}
