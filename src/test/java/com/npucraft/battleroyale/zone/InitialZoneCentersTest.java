package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.map.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitialZoneCentersTest {
    private static MapTemplate map(String id,PlayableArea area) { return new MapTemplate(id,id,Path.of("maps",id),area); }
    private static ZoneProfile profile(Map<String,InitialZoneCenters> centers) {
        var base=ZoneScalingTest.profile();return new ZoneProfile(base.id(),base.initialSizes(),base.stages(),750,centers);
    }
    @Test void singlePresetIsExactAndStillUsesActualPopulationSize() {
        var points=new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(951,862)));
        var profile=profile(Map.of("survival",points));var map=map("survival",new PlayableArea(-500,3000,-500,3000));
        for(int seed=0;seed<100;seed++) {
            assertEquals(new Zone(951,862,200),profile.initialZone(map,4,new Random(seed)));
            assertEquals(new Zone(951,862,750),profile.initialZone(map,32,new Random(seed)));
        }
        profile.validateInitialCenters(map,32);
    }
    @Test void allCandidatesAndFullJitterDiskFitAndSamplingUsesBothCandidates() {
        var first=new InitialZoneCenters.Point(-500,500);var second=new InitialZoneCenters.Point(951,862);
        var centers=new InitialZoneCenters(150,List.of(first,second));var area=new PlayableArea(-2000,3000,-2000,3000);
        var random=new Random(42);var used=new HashSet<InitialZoneCenters.Point>();double squaredDistanceSum=0;
        for(int i=0;i<5000;i++) {
            var square=centers.choose(area,750,random);assertTrue(area.contains(square));
            var nearest=Math.hypot(square.centerX()-first.x(),square.centerZ()-first.z())<150?first:second;
            double distance=Math.hypot(square.centerX()-nearest.x(),square.centerZ()-nearest.z());
            assertTrue(distance<=150+1e-8);squaredDistanceSum+=distance*distance;used.add(nearest);
        }
        assertEquals(Set.of(first,second),used);
        assertEquals(.5,squaredDistanceSum/5000/(150*150),.025,"Uniform disk sampling must not concentrate at the center");
    }
    @Test void everyCandidateIsValidatedBeforeRandomSelectionAndBoundaryTouchingIsAllowed() {
        var exact=new InitialZoneCenters(150,List.of(new InitialZoneCenters.Point(0,0)));
        var area=new PlayableArea(-900,900,-900,900);assertDoesNotThrow(()->exact.validate(area,750));
        var bad=new InitialZoneCenters(150,List.of(new InitialZoneCenters.Point(0,0),new InitialZoneCenters.Point(1,0)));
        assertTrue(assertThrows(IllegalArgumentException.class,()->bad.choose(area,750,new Random(3))).getMessage().contains("points[1]"));
        var invalidCenter=new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(901,0)));
        assertThrows(IllegalArgumentException.class,()->invalidCenter.validate(area,200));
    }
    @Test void mapsWithoutPresetRetainOriginalRandomAlgorithmAndOtherMapPresetsDoNotLeak() {
        var area=new PlayableArea(-3000,3000,-3000,3000);var randomMap=map("desert",area);
        var profile=profile(Map.of("city",new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(951,862)))));
        for(int seed=0;seed<50;seed++)assertEquals(ZoneGeometry.initial(area,300,new Random(seed)),profile.initialZone(randomMap,8,new Random(seed)));
    }
    @Test void laterTargetsAndFinalCenterMoveNaturallyAndAreNeverPinnedToOpeningCandidates() {
        var candidate=new InitialZoneCenters.Point(951,862);var profile=profile(Map.of("survival",new InitialZoneCenters(0,List.of(candidate))));
        var map=map("survival",new PlayableArea(-500,3000,-500,3000));var finals=new HashSet<Zone>();
        for(int seed=0;seed<100;seed++) {
            var random=new Random(seed);var initial=profile.initialZone(map,4,random);var live=new ZoneRuntime(initial,profile,random,0);
            assertNotEquals(initial.centerX(),live.next().centerX());
            assertTrue(live.next().minX()>=initial.minX() && live.next().maxX()<=initial.maxX());
            live.update(430_000_000_000L);assertEquals(ZonePhase.FINAL,live.phase());
            assertTrue(initial.contains(live.current().centerX(),live.current().centerZ()));
            assertNotEquals(new Zone(candidate.x(),candidate.z(),0),live.current());finals.add(live.current());
        }
        assertEquals(100,finals.size());
    }
    @Test void mapGeometryChangesAndNonmonotonicBucketPeaksAreValidated() {
        var centers=new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(500,0)));var base=ZoneScalingTest.profile();
        var profile=new ZoneProfile(base.id(),List.of(new ZoneProfile.InitialSize(4,750),new ZoneProfile.InitialSize(8,200)),base.stages(),750,Map.of("city",centers));
        assertThrows(IllegalArgumentException.class,()->profile.validateInitialCenters(map("city",new PlayableArea(-1000,1000,-1000,1000)),8));
        assertThrows(IllegalArgumentException.class,()->profile.initialZone(map("city",new PlayableArea(-100,600,-500,500)),8,new Random(1)));
    }
    @Test void hashIncludesCenterSettingsInStableMapOrderAndInputsAreImmutable() {
        var points=new ArrayList<>(List.of(new InitialZoneCenters.Point(0,0)));var configured=new InitialZoneCenters(0,points);points.clear();
        assertEquals(1,configured.points().size());assertThrows(UnsupportedOperationException.class,()->configured.points().clear());
        var ab=new LinkedHashMap<String,InitialZoneCenters>();ab.put("a",configured);ab.put("b",configured);
        var ba=new LinkedHashMap<String,InitialZoneCenters>();ba.put("b",configured);ba.put("a",configured);
        assertEquals(profile(ab).toString(),profile(ba).toString());
        assertNotEquals(profile(Map.of()).toString(),profile(ab).toString());
        assertThrows(UnsupportedOperationException.class,()->profile(ab).initialCenters().clear());
    }
    @Test void malformedCenterSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(-1,List.of(new InitialZoneCenters.Point(0,0))));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(0,List.of()));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Point(Double.NaN,0));
        var overflowing=new InitialZoneCenters(Double.MAX_VALUE,List.of(new InitialZoneCenters.Point(Double.MAX_VALUE,0)));
        assertThrows(IllegalArgumentException.class,()->overflowing.validate(new PlayableArea(-1000,1000,-1000,1000),750));
    }
}
