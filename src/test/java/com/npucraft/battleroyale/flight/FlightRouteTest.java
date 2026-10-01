package com.npucraft.battleroyale.flight;

import com.npucraft.battleroyale.zone.Zone;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightRouteTest {
    @Test void randomRoutesStayInsideZoneAndUseBoundedChunks(){
        var random=new Random(72);var routes=new HashSet<FlightRoute>();
        for(int i=0;i<16;i++){
            var zone=new Zone(-9750.75,8240.25,500);var route=FlightRoute.random(zone,random);routes.add(route);
            var chunks=route.chunks();assertTrue(chunks.size()<=160);assertEquals(540,route.length());
            for(var cell:route.corridor(304)){
                assertTrue(zone.contains(cell.x(),cell.z()));
                assertTrue(chunks.contains(new FlightRoute.Chunk(Math.floorDiv(cell.x(),16),Math.floorDiv(cell.z(),16))));
            }
        }
        assertTrue(routes.size()>12);
    }
    @Test void endpointsAndOrientationAreExactForNegativeCoordinates(){
        var route=new FlightRoute(-1,-17,-541,-17);
        assertEquals(new FlightRoute.Cell(-1,304,-17),route.center(-1,304));
        assertEquals(new FlightRoute.Cell(-541,304,-17),route.center(3,304));
        assertEquals(new FlightRoute.Cell(-9,304,-17),route.translate(route.center(0,304),0,0,-8));
        assertEquals(90,route.yaw());
    }
    @Test void invalidAndOversizedCoursesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new FlightRoute(0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new FlightRoute(0,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new FlightRoute(0,0,541,0));
        assertThrows(IllegalArgumentException.class,()->FlightRoute.random(new Zone(0,0,10),new Random()));
    }
}