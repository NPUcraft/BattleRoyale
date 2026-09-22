package com.npucraft.lastsector.zone;
import com.npucraft.lastsector.config.ZoneUiSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class ParticleWallTest {
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

