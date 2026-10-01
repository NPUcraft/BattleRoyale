package com.npucraft.battleroyale.loot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AirdropPlacementTest {
    @Test void repeatedSitesKeepTheirMinimumSeparationInsideBounds() {
        var random=new Random(1234);var used=new ArrayList<AirdropPlacement.Point>();var threshold=150;
        for(int i=0;i<16;i++){
            var point=AirdropPlacement.choose(0,1000,0,1000,threshold,used,random,64);
            assertTrue(point.x()>=0&&point.x()<=1000&&point.z()>=0&&point.z()<=1000,"Site stays inside the requested bounds");
            for(var other:used)assertTrue(Math.hypot(point.x()-other.x(),point.z()-other.z())>=threshold-1e-6,
                    "Sites in one match are at least the configured distance apart");
            used.add(point);
        }
        assertEquals(16,new HashSet<>(used).size(),"Sampling never returns the exact same site twice");
    }
    @Test void impossibleSeparationDegradesGracefullyWithoutLoopingForever() {
        var point=AirdropPlacement.choose(0,10,0,10,1000,List.of(new AirdropPlacement.Point(5,5)),new Random(7),8);
        assertTrue(point.x()>=0&&point.x()<=10&&point.z()>=0&&point.z()<=10,"A degraded site is still inside the zone");
    }
    @Test void emptyUsedListAlwaysAcceptsTheFirstUniformSample() {
        var random=new Random(99);
        for(int i=0;i<100;i++){
            var point=AirdropPlacement.choose(-20,20,-20,20,500,List.of(),random,32);
            assertTrue(point.x()>=-20&&point.x()<=20&&point.z()>=-20&&point.z()<=20);
        }
    }
    @Test void invalidBoundsAndAttemptsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->AirdropPlacement.choose(5,4,0,1,1,List.of(),new Random(1),4));
        assertThrows(IllegalArgumentException.class,()->AirdropPlacement.choose(0,1,0,1,1,List.of(),new Random(1),0));
    }
}
