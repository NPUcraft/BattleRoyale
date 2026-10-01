package com.npucraft.battleroyale.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.npucraft.battleroyale.flight.PlayerLandingGeometry.Box;
import static org.junit.jupiter.api.Assertions.*;

class PlayerLandingGeometryTest {
    @Test void slabsAcceptTheirActualTopRatherThanRoundedBlockY() {
        var bottom=new Box(20,80,0,21,80.5,1);var top=new Box(22,80.5,0,23,81,1);
        assertTrue(PlayerLandingGeometry.standing(20.5,80.5,.5,List.of(bottom)));
        assertFalse(PlayerLandingGeometry.standing(20.5,81,.5,List.of(bottom)));
        assertFalse(PlayerLandingGeometry.standing(20.5,80.4,.5,List.of(bottom)));
        assertTrue(PlayerLandingGeometry.standing(22.5,81,.5,List.of(top)));
    }
    @Test void stairsUseIndividualCollisionBoxesIncludingTheUnoccupiedLowerHalf() {
        var lower=new Box(46,80,0,47,80.5,1);var higher=new Box(46.5,80.5,0,47,81,1);
        var stairs=List.of(lower,higher);
        assertTrue(PlayerLandingGeometry.standing(46.15,80.5,.5,stairs));
        assertTrue(PlayerLandingGeometry.standing(46.8,81,.5,stairs));
        assertFalse(PlayerLandingGeometry.standing(46.5,80.5,.5,stairs),"A riser intersecting the body is not standing room");
    }
    @Test void surfaceContactAllowsSmallPositionRoundingButNotAirborneOrSideContact() {
        var floor=new Box(0,0,0,1,1,1);
        assertTrue(PlayerLandingGeometry.standing(.5,1+1e-8,.5,List.of(floor)));
        assertTrue(PlayerLandingGeometry.standing(.5,1.02,.5,List.of(floor)));
        assertFalse(PlayerLandingGeometry.standing(.5,1.04,.5,List.of(floor)));
        assertFalse(PlayerLandingGeometry.standing(1.3,1,.5,List.of(floor)),"A shared edge has no bearing surface");
        assertFalse(PlayerLandingGeometry.standing(.5,1,.5,List.of()));
        assertFalse(PlayerLandingGeometry.standing(.5,Double.NaN,.5,List.of(floor)));
    }
    @Test void standingHeadroomAndFootprintPreventSuffocationAfterElytraRemoval() {
        var floor=new Box(0,0,0,1,1,1);
        assertFalse(PlayerLandingGeometry.standing(.5,1,.5,List.of(floor,new Box(0,2,0,1,3,1))));
        assertFalse(PlayerLandingGeometry.standing(.8,1,.5,List.of(floor,new Box(1,1,0,2,2,1))));
        assertTrue(PlayerLandingGeometry.standing(.5,1,.5,List.of(floor,new Box(0,2.8,0,1,3.8,1))));
    }
    @Test void narrowRaisedSurfacesAndNegativeCoordinatesRemainValid() {
        assertTrue(PlayerLandingGeometry.standing(-.5,-.5,-.5,List.of(new Box(-.625,-2,-.625,-.375,-.5,-.375))));
        assertTrue(PlayerLandingGeometry.standing(.5,81.0625,.5,List.of(new Box(0,81,0,1,81.0625,1))));
    }
}
