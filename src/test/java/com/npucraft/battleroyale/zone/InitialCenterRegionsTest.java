package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.map.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitialCenterRegionsTest {
    private final PlayableArea area=new PlayableArea(-5000,5000,-5000,5000);
    private final InitialZoneCenters.Region spawn=new InitialZoneCenters.Region("spawn","出生点附近",751,1151,662,1062);
    private final InitialZoneCenters.Region west=new InitialZoneCenters.Region("west","Western village",-2500,-1500,-1200,-400);
    private InitialZoneCenters centers(){return new InitialZoneCenters(List.of(spawn,west));}
    private MapTemplate map(String id,PlayableArea bounds){return new MapTemplate(id,id,Path.of("maps",id),bounds);}
    private ZoneProfile profile(InitialZoneCenters centers){
        var base=ZoneScalingTest.profile();return new ZoneProfile(base.id(),base.initialSizes(),base.stages(),750,Map.of("survival",centers));
    }
    @Test void selectedRegionSamplesBothAxesUniformlyWithoutAnotherRegionLottery(){
        var centers=centers();var random=new Random(401);double x=0,z=0;var seen=new HashSet<InitialZoneCenters.Point>();
        for(int i=0;i<5000;i++){
            var zone=centers.choose(area,750,random,"spawn");assertTrue(spawn.contains(zone.centerX(),zone.centerZ()));assertTrue(area.contains(zone));
            seen.add(new InitialZoneCenters.Point(zone.centerX(),zone.centerZ()));x+=zone.centerX();z+=zone.centerZ();
        }
        assertEquals(5000,seen.size());assertEquals(951,x/5000,8);assertEquals(862,z/5000,8);
        var noLottery=new Random(7){@Override public int nextInt(int bound){throw new AssertionError("Frozen region must not be selected again");}};
        assertDoesNotThrow(()->centers.choose(area,200,noLottery,"west"));
    }
    @Test void noExplicitRegionSelectsRegionsUniformlyRatherThanWeightingByRectangleArea(){
        var random=new Random(803);int first=0,second=0;
        for(int i=0;i<8000;i++){var zone=centers().choose(area,750,random);if(spawn.contains(zone.centerX(),zone.centerZ()))first++;else{assertTrue(west.contains(zone.centerX(),zone.centerZ()));second++;}}
        assertEquals(4000,first,180);assertEquals(4000,second,180);
    }
    @Test void everyRegionMustFitIncludingLargestInitialSquareAndTouchingBoundaryIsAllowed(){
        var exact=new InitialZoneCenters(List.of(new InitialZoneCenters.Region("edge","Edge",-4250,4250,-4250,4250)));
        assertDoesNotThrow(()->exact.validate(area,750));
        var out=new InitialZoneCenters(List.of(spawn,new InitialZoneCenters.Region("bad","Outside",4250,4251,0,1)));
        var error=assertThrows(IllegalArgumentException.class,()->out.choose(area,750,new Random(1),"spawn"));
        assertTrue(error.getMessage().contains("regions[1]"));assertTrue(error.getMessage().contains("id=bad"));
        var base=ZoneScalingTest.profile();
        var profile=new ZoneProfile("peak",List.of(new ZoneProfile.InitialSize(4,750),new ZoneProfile.InitialSize(8,200)),base.stages(),750,Map.of("survival",centers()));
        assertThrows(IllegalArgumentException.class,()->profile.validateInitialCenters(map("survival",new PlayableArea(-3000,2000,-2000,2000)),8));
    }
    @Test void explicitSelectionChangesOpeningOnlyAndKeepsScaledLaterCentersUnrestricted(){
        var profile=profile(centers());var random=new Random(91);
        Zone initial=profile.initialZone(map("survival",area),4,random,"spawn");assertEquals(200,initial.halfSize());
        var runtime=new ZoneRuntime(initial,profile,random,0);assertNotEquals(initial.centerX(),runtime.next().centerX());
        assertTrue(initial.contains(runtime.next().centerX(),runtime.next().centerZ()));
        runtime.update(430_000_000_000L);assertEquals(ZonePhase.FINAL,runtime.phase());assertEquals(0,runtime.current().halfSize());
        assertNotEquals(initial.centerX(),runtime.current().centerX());
        assertEquals(600,profile.stages().getFirst().targetHalfSize(),"Opening choice never mutates configured target sizes");
    }
    @Test void unknownRegionAndRegionForWrongMapOrPointModeFailClosed(){
        var profile=profile(centers());
        assertThrows(IllegalArgumentException.class,()->profile.initialZone(map("survival",area),4,new Random(1),"unknown"));
        assertThrows(IllegalArgumentException.class,()->profile.initialZone(map("desert",area),4,new Random(1),"spawn"));
        var points=new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(951,862)));
        assertThrows(IllegalArgumentException.class,()->points.choose(area,200,new Random(1),"spawn"));
    }
    @Test void legacyPointStringAndRandomConsumptionStayIdentical(){
        var point=new InitialZoneCenters.Point(951,862);var centers=new InitialZoneCenters(0,List.of(point));
        assertEquals("InitialZoneCenters[jitterRadius=0.0, points=[Point[x=951.0, z=862.0]]]",centers.toString());
        for(int seed=0;seed<50;seed++){
            var actualRandom=new Random(seed);var expectedRandom=new Random(seed);expectedRandom.nextInt(1);
            assertEquals(new Zone(951,862,750),centers.choose(area,750,actualRandom));assertEquals(expectedRandom.nextLong(),actualRandom.nextLong());
        }
        assertEquals(centers,new InitialZoneCenters(0,List.of(point),List.of()));
        assertTrue(centers.regions().isEmpty());
    }
    @Test void namedGeometryIsImmutableAndChangesRulesHashRepresentation(){
        var source=new ArrayList<>(List.of(spawn));var centers=new InitialZoneCenters(source);source.clear();
        assertEquals(List.of(spawn),centers.regions());assertThrows(UnsupportedOperationException.class,()->centers.regions().clear());
        assertTrue(centers.toString().contains("regions=[Region[id=spawn"));
        var moved=new InitialZoneCenters(List.of(new InitialZoneCenters.Region("spawn","出生点附近",752,1151,662,1062)));
        assertNotEquals(profile(centers).toString(),profile(moved).toString());
    }
    @Test void invalidRegionIdentifiersNamesBoundsAndMixedModesAreRejected(){
        for(String id:List.of("","A","has space","../escape","x".repeat(49),"_leading"))assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Region(id,"Name",0,1,0,1));
        for(String name:List.of(""," "," leading","trailing ","line\nbreak","legacy§a","x".repeat(65)))assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Region("id",name,0,1,0,1));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Region("id","Name",1,1,0,1));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Region("id","Name",0,1,2,1));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Region("id","Name",Double.NaN,1,0,1));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters.Region("id","Name",-Double.MAX_VALUE,Double.MAX_VALUE,0,1));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(List.of()));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(List.of(spawn,spawn)));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(1,List.of(),List.of(spawn)));
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(0,0)),List.of(spawn)));
        var tooMany=IntStream.rangeClosed(0,InitialZoneCenters.MAX_REGIONS).mapToObj(i->new InitialZoneCenters.Region("id-"+i,"Region "+i,0,1,0,1)).toList();
        assertThrows(IllegalArgumentException.class,()->new InitialZoneCenters(tooMany));
        assertDoesNotThrow(()->new InitialZoneCenters(tooMany.subList(0,InitialZoneCenters.MAX_REGIONS)));
    }
}