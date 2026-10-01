package com.npucraft.battleroyale.config;

import com.npucraft.battleroyale.admin.*;
import com.npucraft.battleroyale.loot.MapLoot;
import com.npucraft.battleroyale.map.PlayableArea;
import com.npucraft.battleroyale.zone.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class InitialRegionConfigurationTest {
    @TempDir Path directory;
    private static final String SPAWN="{id: spawn, name: 出生点附近, min-x: 751, max-x: 1151, min-z: 662, max-z: 1062}";
    private String zones;
    @BeforeEach void defaults()throws Exception{
        for(String file:List.of("config.yml","rooms.yml","maps.yml","zones.yml"))
            try(var stream=getClass().getResourceAsStream("/"+file)){
                Files.writeString(directory.resolve(file),new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n"));
            }
        zones=Files.readString(directory.resolve("zones.yml"));
    }
    private void regions(String list,String extra)throws Exception{
        Files.writeString(directory.resolve("zones.yml"),zones.replace("    stages:","    initial-centers:\n      city:\n        regions: "+list+"\n"+extra+"    stages:"));
    }
    private ConfigurationSnapshot load(){return new ConfigurationLoader(directory).load();}
    @Test void namedRegionsLoadWithLiteralNamesWithoutFileChangesAndAffectOnlyTheirMap()throws Exception{
        regions("["+SPAWN+", {id: west, name: 'West & <literal>', min-x: -1500, max-x: -500, min-z: -500, max-z: 500}]","");
        byte[] before=Files.readAllBytes(directory.resolve("zones.yml"));var result=load();var profile=result.zoneProfiles().getFirst();
        var settings=profile.initialCenters().get("city");assertEquals(2,settings.regions().size());assertTrue(settings.points().isEmpty());assertEquals(0,settings.jitterRadius());
        assertEquals("出生点附近",settings.region("spawn").orElseThrow().name());assertEquals("West & <literal>",settings.region("west").orElseThrow().name());
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("zones.yml")));
        var city=result.maps().stream().filter(map->map.id().equals("city")).findFirst().orElseThrow();
        var desert=result.maps().stream().filter(map->map.id().equals("desert")).findFirst().orElseThrow();
        for(int seed=0;seed<100;seed++){
            var opening=profile.initialZone(city,32,new Random(seed),"spawn");assertEquals(750,opening.halfSize());assertTrue(settings.region("spawn").orElseThrow().contains(opening.centerX(),opening.centerZ()));
            assertEquals(ZoneGeometry.initial(desert.playableArea(),200,new Random(seed)),profile.initialZone(desert,4,new Random(seed)));
        }
    }
    static Stream<Arguments> invalid(){
        return Stream.of(
                Arguments.of("[]","regions"),
                Arguments.of("[{id: upperCase, name: A, min-x: 0, max-x: 1, min-z: 0, max-z: 1}]","regions[0]"),
                Arguments.of("[{id: valid, name: ' ', min-x: 0, max-x: 1, min-z: 0, max-z: 1}]","regions[0].name"),
                Arguments.of("[{id: valid, min-x: 0, max-x: 1, min-z: 0, max-z: 1}]","regions[0].name"),
                Arguments.of("[{id: valid, name: A, min-x: 1, max-x: 1, min-z: 0, max-z: 1}]","regions[0]"),
                Arguments.of("[{id: valid, name: A, min-x: 0, max-x: 1, min-z: 1, max-z: 0}]","regions[0]"),
                Arguments.of("[{id: valid, name: A, min-x: .NaN, max-x: 1, min-z: 0, max-z: 1}]","regions[0].min-x"),
                Arguments.of("[{id: valid, name: A, min-x: 0, max-x: 1, min-z: 0}]","regions[0].max-z"),
                Arguments.of("[{id: valid, name: A, min-x: zero, max-x: 1, min-z: 0, max-z: 1}]","regions[0].min-x"),
                Arguments.of("[{id: valid, name: A, min-x: 0, max-x: 1, min-z: 0, max-z: 1, unknown: true}]","regions[0].unknown"),
                Arguments.of("["+SPAWN+", "+SPAWN+"]","regions[1].id"),
                Arguments.of("[{id: outside, name: Outside, min-x: 2250, max-x: 2251, min-z: 0, max-z: 1}]","room=solo map=city profile=default"),
                Arguments.of("["+SPAWN+", {id: outside, name: Outside, min-x: 0, max-x: 1, min-z: -2251, max-z: -2250}]","initial-centers.regions[1]"));
    }
    @ParameterizedTest @MethodSource("invalid")
    void invalidRegionsFailWithExactConfigurationContext(String list,String context)throws Exception{
        regions(list,"");String error=assertThrows(ConfigurationException.class,this::load).getMessage();assertTrue(error.contains(context),error);
    }
    @Test void pointsAndRegionsCannotMixAndJitterCannotExpandRegionBounds()throws Exception{
        regions("["+SPAWN+"]","        points: [{x: 951, z: 862}]\n");
        assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains("mutually exclusive"));
        regions("["+SPAWN+"]","        jitter-radius: 20\n");
        assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains("jitter-radius"));
        regions("["+SPAWN+"]","        jitter-radius: 0\n");assertDoesNotThrow(this::load);
    }
    @Test void regionBudgetIsBoundedBeforePublishing()throws Exception{
        String list=IntStream.rangeClosed(0,InitialZoneCenters.MAX_REGIONS).mapToObj(i->"{id: r"+i+", name: R"+i+", min-x: 0, max-x: 1, min-z: 0, max-z: 1}").collect(Collectors.joining(", ","[","]"));
        regions(list,"");assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains("at most 64"));
    }
    @Test void boundaryTouchingLargestRoomSquareIsLegal()throws Exception{
        regions("[{id: edge, name: 边缘, min-x: -2250, max-x: 2250, min-z: -2250, max-z: 2250}]","");assertDoesNotThrow(this::load);
    }
    @Test void runtimeMetadataChangesRevalidateTheEntireVotingRegion()throws Exception{
        regions("["+SPAWN+"]","");var config=load();var map=config.maps().stream().filter(value->value.id().equals("city")).findFirst().orElseThrow();
        var shifted=new MapMetadata(1,1,map.displayName(),new PlayableArea(-1000,1850,-1000,1900),new MapLoot(List.of(),List.of()),null);
        var result=new MapValidationService().metadata(map,shifted,config,Set.of());assertFalse(result.valid());assertTrue(result.text().contains("Initial center invalid for room solo"));
        var fitting=new MapMetadata(1,2,map.displayName(),new PlayableArea(-1000,1901,-1000,1812),shifted.loot(),null);
        assertTrue(new MapValidationService().metadata(map,fitting,config,Set.of()).valid());
    }
    @Test void isolatedMapLoadingDefersGeometryButNeverAcceptsInvalidRegionSyntax()throws Exception{
        regions("[{id: outside, name: Outside, min-x: 2400, max-x: 2500, min-z: 0, max-z: 1}]","");
        var isolated=new ConfigurationLoader(directory,true).load();var map=isolated.maps().getFirst();
        assertFalse(new MapValidationService().metadata(map,MapMetadata.initial(map,new MapLoot(List.of(),List.of())),isolated,Set.of()).valid());
        assertThrows(IllegalArgumentException.class,()->isolated.zoneProfiles().getFirst().initialZone(map,32,new Random(1),"outside"));
        regions("["+SPAWN+", "+SPAWN+"]","");assertThrows(ConfigurationException.class,()->new ConfigurationLoader(directory,true).load());
    }
}