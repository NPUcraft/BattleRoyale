package com.npucraft.battleroyale.map;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.zone.Zone;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldFilesRegionCopyTest {
    @TempDir Path root;
    Path data, template;
    MapTemplate map;
    WorldFiles files;
    private static final List<String> ANVIL = List.of("region", "entities", "poi");

    @BeforeEach void setup() throws Exception {
        data = Files.createDirectories(root.resolve("plugins/BattleRoyale"));
        template = Files.createDirectories(data.resolve("maps/survival"));
        Path settings = template.resolve(LevelData.WORLD_GEN_SETTINGS);
        Files.createDirectories(settings.getParent());
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(settings)))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(4903);
            out.writeByte(10); out.writeUTF("data");
            out.writeByte(4); out.writeUTF("seed"); out.writeLong(-487291L);
            out.writeByte(10); out.writeUTF("dimensions");
            out.writeByte(10); out.writeUTF("minecraft:overworld");
            out.writeByte(8); out.writeUTF("type"); out.writeUTF("minecraft:overworld");
            out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(0);
        }
        map = new MapTemplate("survival", "Survival", template, new PlayableArea(-10000,10000,-10000,10000));
        files = files(RuntimeLayout.GAME);
    }
    private WorldFiles files(String namespace) throws IOException {
        return WorldFiles.paper262(data, new RuntimeLayout(root.resolve("world"), namespace), List.of(template),
                List.of(root.resolve("world/dimensions/minecraft/overworld")));
    }
    private void entry(String relative) throws IOException {
        Path file = template.resolve(relative); Files.createDirectories(file.getParent());
        Files.writeString(file, "original " + relative);
    }
    private Map<String,String> snapshot() throws IOException {
        var result = new TreeMap<String,String>();
        try (var paths = Files.walk(template)) {
            for (Path file : paths.filter(Files::isRegularFile).toList())
                result.put(template.relativize(file).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
        return result;
    }
    @Test void negativeFractionalBoundsKeepIntersectingRegionsAndEveryReferencedExternalChunk() throws Exception {
        // Initial [-512.5,-512] plus 512 blocks becomes [-1024.5,0], intersecting regions -3..0.
        for (String directory : ANVIL) {
            for (String name : List.of("r.-3.-3.mca", "r.-1.-1.mca", "r.0.0.mca", "r.-4.0.mca", "r.1.0.mca", "r.0.1.mca",
                    "c.-65.-65.mcc", "c.-96.-96.mcc", "c.-1.-1.mcc", "c.31.31.mcc", "c.-97.0.mcc", "c.32.0.mcc", "c.0.32.mcc"))
                entry(directory + "/" + name);
        }
        entry("data/custom/r.99.99.mca");
        entry("data/paper/level_overrides.dat");
        entry("data/paper/metadata.dat");
        var before = snapshot();
        var world = files.copy(UUID.randomUUID(), "solo", map, new Zone(-512.25,-512.25,.25));
        for (String directory : ANVIL) {
            for (String name : List.of("r.-3.-3.mca", "r.-1.-1.mca", "r.0.0.mca", "c.-65.-65.mcc", "c.-96.-96.mcc", "c.-1.-1.mcc", "c.31.31.mcc"))
                assertTrue(Files.exists(world.runtimePath().resolve(directory + "/" + name)), directory + "/" + name);
            for (String name : List.of("r.-4.0.mca", "r.1.0.mca", "r.0.1.mca", "c.-97.0.mcc", "c.32.0.mcc", "c.0.32.mcc"))
                assertFalse(Files.exists(world.runtimePath().resolve(directory + "/" + name)), directory + "/" + name);
        }
        assertTrue(Files.exists(world.runtimePath().resolve("data/custom/r.99.99.mca")));
        assertTrue(Files.exists(world.runtimePath().resolve("data/paper/level_overrides.dat")));
        assertFalse(Files.exists(world.runtimePath().resolve("data/paper/metadata.dat")));
        assertEquals(-487291L, world.expectedSeed());
        assertArrayEquals(Files.readAllBytes(template.resolve(LevelData.WORLD_GEN_SETTINGS)),
                Files.readAllBytes(world.runtimePath().resolve(LevelData.WORLD_GEN_SETTINGS)));
        assertEquals(before, snapshot());
        assertEquals(world, files.recovery(world.sessionId(), world.roomId(), map, world.worldName(), world.runtimePath().getFileName().toString()));
        files.delete(world, true);
        assertEquals(before, snapshot());
    }
    @Test void exactPositiveAndNegativeBoundariesIncludeTheirContainingRegion() throws Exception {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) entry("region/r." + x + "." + z + ".mca");
        var world = files.copy(UUID.randomUUID(), "solo", map, new Zone(0,0,512));
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            assertEquals(x >= -2 && x <= 2 && z >= -2 && z <= 2,
                    Files.exists(world.runtimePath().resolve("region/r." + x + "." + z + ".mca")), x + "," + z);
    }
    @Test void noAreaAndMaintenanceOverloadsStillCopyTheCompleteTemplate() throws Exception {
        for (String directory : ANVIL) { entry(directory + "/r.19.-20.mca"); entry(directory + "/c.639.-640.mcc"); }
        var full = files.copy(UUID.randomUUID(), "solo", map);
        var maintenance = files(RuntimeLayout.MAINTENANCE);
        var pregeneration = maintenance.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID());
        var editor = maintenance.copy(UUID.randomUUID(), "maintenance", map, "EDITOR", UUID.randomUUID());
        for (var world : List.of(full, pregeneration, editor)) for (String directory : ANVIL) {
            assertTrue(Files.exists(world.runtimePath().resolve(directory + "/r.19.-20.mca")));
            assertTrue(Files.exists(world.runtimePath().resolve(directory + "/c.639.-640.mcc")));
        }
    }
    @Test void oldStorageLayoutAlsoKeepsTheUnscopedCopyContract() throws Exception {
        TestSupport.level(template.resolve("level.dat"), -487291L);
        entry("region/r.19.-20.mca"); entry("region/c.639.-640.mcc");
        var oldFiles = new WorldFiles(data, data.resolve("legacy-runtime"), root, List.of(template), List.of());
        var world = oldFiles.copy(UUID.randomUUID(), "solo", map);
        assertTrue(Files.exists(world.runtimePath().resolve("region/r.19.-20.mca")));
        assertTrue(Files.exists(world.runtimePath().resolve("region/c.639.-640.mcc")));
    }
}
