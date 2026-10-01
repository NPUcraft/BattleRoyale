package com.npucraft.battleroyale.config;

import com.npucraft.battleroyale.admin.*;
import com.npucraft.battleroyale.loot.MapLoot;
import com.npucraft.battleroyale.map.PlayableArea;
import com.npucraft.battleroyale.recovery.SnapshotCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class InitialCenterConfigurationTest {
    @TempDir Path directory;
    @BeforeEach void defaults() throws Exception {
        for(String file:List.of("config.yml","rooms.yml","maps.yml","zones.yml"))
            try(var stream=getClass().getResourceAsStream("/"+file)){
                Files.writeString(directory.resolve(file),new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n"));
            }
    }
    private ConfigurationSnapshot load(){return new ConfigurationLoader(directory).load();}
    private void centers(String map,String jitter,String points)throws Exception {
        Path file=directory.resolve("zones.yml");String yaml=Files.readString(file);
        Files.writeString(file,yaml.replace("    stages:","    initial-centers:\n      "+map+":\n        jitter-radius: "+jitter+"\n        points: "+points+"\n    stages:"));
    }
    @Test void configuredCandidatesLoadWithoutChangingFileAndOnlyAffectTheirMap()throws Exception {
        centers("city","150","[{x: 951, z: 862}, {x: -500, z: -500}]");
        byte[] bytes=Files.readAllBytes(directory.resolve("zones.yml"));var result=load();var zone=result.zoneProfiles().getFirst();
        assertEquals(2,zone.initialCenters().get("city").points().size());assertEquals(150,zone.initialCenters().get("city").jitterRadius());
        assertFalse(zone.initialCenters().containsKey("desert"));assertArrayEquals(bytes,Files.readAllBytes(directory.resolve("zones.yml")));
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "unknown|0|[{x: 0, z: 0}]|initial-centers.unknown",
        "city|-1|[{x: 0, z: 0}]|initial-centers.city.jitter-radius",
        "city|.NaN|[{x: 0, z: 0}]|initial-centers.city.jitter-radius",
        "city|0|[]|initial-centers.city.points",
        "city|0|[{x: .NaN, z: 0}]|initial-centers.city.points[0].x",
        "city|0|[{x: 0}]|initial-centers.city.points[0].z",
        "city|0|[{x: 2500, z: 0}]|room=solo map=city profile=default",
        "city|200|[{x: 2200, z: 0}]|initial-centers.points[0]",
        "city|0|[{x: 0, z: 0}, {x: 2500, z: 0}]|initial-centers.points[1]"
    }) void rejectsInvalidCandidatesWithUsefulContext(String map,String jitter,String points,String context)throws Exception {
        centers(map,jitter,points);String error=assertThrows(ConfigurationException.class,this::load).getMessage();assertTrue(error.contains(context),error);
    }
    @Test void runtimeMetadataOverlayCannotMovePlayableBoundsAwayFromConfiguredCenter()throws Exception {
        centers("city","0","[{x: 500, z: 0}]");var snapshot=load();var map=snapshot.maps().getFirst();
        var metadata=new MapMetadata(1,1,map.displayName(),new PlayableArea(-1000,1000,-1000,1000),new MapLoot(List.of(),List.of()),null);
        var report=new MapValidationService().metadata(map,metadata,snapshot,Set.of());
        assertFalse(report.valid());assertTrue(report.text().contains("Initial center invalid for room solo"));
        assertFalse(report.text().contains("Playable area cannot contain"),"Area dimensions fit; its position is the actual failure");
        var fitting=new MapMetadata(1,2,map.displayName(),new PlayableArea(-1000,1250,-1000,1000),metadata.loot(),null);
        assertTrue(new MapValidationService().metadata(map,fitting,snapshot,Set.of()).valid());
    }
    @Test void runtimeMapIsolationDefersGeometryToPerMapValidationWithoutAcceptingInvalidGlobalSyntax()throws Exception {
        centers("city","0","[{x: 2500, z: 0}]");
        var isolated=new ConfigurationLoader(directory,true).load();var map=isolated.maps().getFirst();
        var metadata=MapMetadata.initial(map,new MapLoot(List.of(),List.of()));
        assertFalse(new MapValidationService().metadata(map,metadata,isolated,Set.of()).valid());
        assertThrows(IllegalArgumentException.class,()->isolated.zoneProfiles().getFirst().initialZone(map,32,new Random(1)));
    }
    @Test void absentAndExplicitZeroReferenceRetainSameLegacyHashAndAbsoluteTargets()throws Exception {
        Path path=directory.resolve("zones.yml");String current=Files.readString(path);
        assertTrue(current.contains("    stage-reference-half-size: 750\n"));
        String legacy=current.replace("    stage-reference-half-size: 750\n","")
                .replace("target-half-size: 600","target-half-size: 160").replace("target-half-size: 300","target-half-size: 80").replace("target-half-size: 100","target-half-size: 25");
        Files.writeString(path,legacy);var absent=load().zoneProfiles().getFirst();assertEquals(0,absent.stageReferenceHalfSize());
        assertEquals(160,absent.resolved(750).stages().getFirst().targetHalfSize());
        Files.writeString(path,legacy.replace("  default:\n","  default:\n    stage-reference-half-size: 0\n"));
        var explicit=load().zoneProfiles().getFirst();assertEquals(absent,explicit);assertEquals(SnapshotCodec.hash(absent.toString()),SnapshotCodec.hash(explicit.toString()));
    }
    @Test void invalidScaleReferenceCannotPublish()throws Exception {
        Path path=directory.resolve("zones.yml");Files.writeString(path,Files.readString(path).replace("stage-reference-half-size: 750","stage-reference-half-size: 127"));
        assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains("stage-reference-half-size"));
    }
}
