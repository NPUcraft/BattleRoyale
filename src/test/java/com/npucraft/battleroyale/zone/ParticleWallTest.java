package com.npucraft.battleroyale.zone;
import com.npucraft.battleroyale.config.ZoneUiSettings;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class ParticleWallTest {
    private static ZoneUiSettings cap(int cap) {
        return new ZoneUiSettings(true,5,true,"END_ROD",5,64,2.5,1.5,3,6,cap);
    }
    @Test void tinyBudgetSharesNearbyCornerEdgesInsteadOfExhaustingTheFirstEdge() {
        var points=ParticleWall.sample(new Zone(0,0,100),98,60,98,cap(2));
        assertEquals(2,points.size());
        assertTrue(points.stream().anyMatch(p->p.x()==100 && p.z()==98));
        assertTrue(points.stream().anyMatch(p->p.x()==98 && p.z()==100));
        assertTrue(points.stream().allMatch(p->Math.abs(p.y()-61.6)<1e-8));
    }
    @Test void nearestPointIsDrawnBeforeTheFarEndOfTheVisibleSegment() {
        var points=ParticleWall.sample(new Zone(0,0,100),99,60,40,cap(1));
        assertEquals(1,points.size()); assertEquals(100,points.getFirst().x()); assertEquals(40,points.getFirst().z());
    }
    @Test void allFourVisibleEdgesReceiveBudgetEvenInSmallFinalCircle() {
        var points=ParticleWall.sample(new Zone(0,0,5),0,60,0,cap(4));
        assertEquals(4,points.size());
        assertTrue(points.stream().anyMatch(p->p.x()==-5)); assertTrue(points.stream().anyMatch(p->p.x()==5));
        assertTrue(points.stream().anyMatch(p->p.z()==-5)); assertTrue(points.stream().anyMatch(p->p.z()==5));
    }
    @Test void extremeAllowedDensityStillRespectsCapLocalDiskAndNoDuplicateSamples() {
        var settings=new ZoneUiSettings(true,5,true,"END_ROD",5,256,.25,.25,64,64,1000);
        var points=ParticleWall.sample(new Zone(0,0,50000),49999,80,49999,settings);
        assertEquals(1000,points.size()); assertEquals(points.size(),new HashSet<>(points).size());
        for (var point:points) {
            assertTrue(Math.hypot(point.x()-49999,point.z()-49999)<=256+1e-8);
            assertTrue(point.y()>=16 && point.y()<=144);
        }
    }
    @Test void totalMapPerimeterDoesNotChangeLocalSamplingCostOrPattern() {
        var smaller=ParticleWall.sample(new Zone(0,0,500),499,60,100,ZoneUiSettings.DEFAULT);
        var larger=ParticleWall.sample(new Zone(0,0,50000),49999,60,100,ZoneUiSettings.DEFAULT);
        assertEquals(smaller.size(),larger.size());
        for (int i=0;i<smaller.size();i++) {
            assertEquals(smaller.get(i).x()+49500,larger.get(i).x());
            assertEquals(smaller.get(i).y(),larger.get(i).y()); assertEquals(smaller.get(i).z(),larger.get(i).z());
        }
    }

    @ParameterizedTest @ValueSource(doubles={500,5000,50000})
    void wallIsLocalCappedAndOnEdges(double half) {
        var s=ZoneUiSettings.DEFAULT; var zone=new Zone(0,0,half);
        assertTrue(ParticleWall.sample(zone,0,60,0,s).isEmpty());
        var points=ParticleWall.sample(zone,half-2,60,half-2,s);
        assertFalse(points.isEmpty()); assertTrue(points.size()<=300);
        for(var p:points) {
            assertTrue(p.x()==half || p.z()==half);
            assertTrue(Math.hypot(p.x()-(half-2),p.z()-(half-2))<=64.00000001);
            assertTrue(p.y()>=57 && p.y()<=66);
        }
        assertTrue(points.stream().anyMatch(p -> p.x()==half));
        assertTrue(points.stream().anyMatch(p -> p.z()==half));
    }
}
