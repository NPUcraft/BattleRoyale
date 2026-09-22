package com.npucraft.lastsector.map;
import com.npucraft.lastsector.TestSupport;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldFilesTest {
    @TempDir Path root;
    Path data, runtime, template, lobby;
    WorldFiles files;
    MapTemplate map;
    UUID id;
    @BeforeEach void setup() throws Exception {
        data = Files.createDirectories(root.resolve("plugins/LastSector")); runtime = data.resolve("runtime");
        template = Files.createDirectories(data.resolve("maps/city")); lobby = Files.createDirectory(root.resolve("world"));
        TestSupport.level(template.resolve("level.dat"), 123456789L);
        for (String file : List.of("region/r.0.0.mca", "entities/r.0.0.mca", "poi/r.0.0.mca", "data/raids.dat",
                "datapacks/example/pack.mcmeta", "uid.dat", "session.lock", "playerdata/user.dat", "stats/user.json", "advancements/user.json")) {
            Path path = template.resolve(file); Files.createDirectories(path.getParent()); Files.writeString(path, file);
        }
        map = TestSupport.map(template); id = UUID.randomUUID();
        files = new WorldFiles(data, runtime, root, List.of(template), List.of(lobby));
    }
    @Test void orphanAgeSurvivesRestartAndCannotDeleteReferencedOrLoadedWorld() throws Exception {
        var world=files.copy(id,"solo",map);var time=java.time.Instant.parse("2026-01-01T00:00:00Z");files.orphan(world,time,"test");files.orphan(world,time.plusSeconds(999),"again");
        var restarted=new WorldFiles(data,runtime,root,List.of(template),List.of(lobby));var candidate=restarted.ownedChildren(message->{}).getFirst();assertEquals(time,candidate.orphanedAt());
        assertFalse(restarted.deleteOrphan(candidate,time.plusSeconds(3599),java.time.Duration.ofHours(1),Set.of(),Set.of()));
        assertFalse(restarted.deleteOrphan(candidate,time.plusSeconds(3600),java.time.Duration.ofHours(1),Set.of(world.runtimePath()),Set.of()));
        assertFalse(restarted.deleteOrphan(candidate,time.plusSeconds(3600),java.time.Duration.ofHours(1),Set.of(),Set.of(id)));
        assertTrue(restarted.deleteOrphan(candidate,time.plusSeconds(3600),java.time.Duration.ofHours(1),Set.of(),Set.of()));assertFalse(Files.exists(world.runtimePath()));
    }
    @Test void recoveryUsesReDerivedPathAndRequiresActiveMarker() throws Exception {
        var world=files.copy(id,"solo",map);assertEquals(world.runtimePath(),files.recovery(id,"solo",map,world.worldName(),world.runtimePath().getFileName().toString()).runtimePath());
        assertThrows(IOException.class,()->files.recovery(id,"solo",map,world.worldName(),"../world"));files.orphan(world,java.time.Instant.now(),"test");
        assertThrows(IOException.class,()->files.recovery(id,"solo",map,world.worldName(),world.runtimePath().getFileName().toString()));
    }
    @Test void scannerKeepsUnmarkedAndMalformedChildren()throws Exception{Files.createDirectories(runtime.resolve("unmarked"));var world=files.copy(id,"solo",map);Files.writeString(world.runtimePath().resolve(WorldFiles.MARKER),"bad");assertTrue(files.ownedChildren(message->{}).isEmpty());assertTrue(Files.exists(runtime.resolve("unmarked")));}
    @Test void copiesWorldDataPreservingSeedAndSourceWithExplicitExclusions() throws Exception {
        byte[] original = Files.readAllBytes(template.resolve("level.dat"));
        GameWorld world = files.copy(id, "solo", map);
        for (String file : List.of("level.dat", "region/r.0.0.mca", "entities/r.0.0.mca", "poi/r.0.0.mca",
                "data/raids.dat", "datapacks/example/pack.mcmeta", WorldFiles.MARKER))
            assertTrue(Files.isRegularFile(world.runtimePath().resolve(file)), file);
        for (String name : List.of("uid.dat", "session.lock", "playerdata", "stats", "advancements"))
            assertFalse(Files.exists(world.runtimePath().resolve(name)), name);
        assertArrayEquals(original, Files.readAllBytes(world.runtimePath().resolve("level.dat")));
        assertArrayEquals(original, Files.readAllBytes(template.resolve("level.dat")));
        assertEquals(123456789L, world.expectedSeed());
        assertTrue(Files.exists(template.resolve("uid.dat")));
        assertTrue(Files.exists(template.resolve("playerdata/user.dat")));
        assertTrue(Files.readString(world.runtimePath().resolve(WorldFiles.MARKER)).contains(id.toString()));
    }
    @Test void namesAreSafeUniqueAndCannotInjectPaths() {
        String name = WorldFiles.leafName("../../世界\\a:b", id);
        assertTrue(name.matches("ls_[a-z0-9_-]+_[a-f0-9]{32}"), name);
        assertNotEquals(name, WorldFiles.leafName("../../世界\\a:b", UUID.randomUUID()));
        var world = files.descriptor(id, "../../room", map);
        assertEquals(runtime, world.runtimePath().getParent());
        assertEquals(world.runtimePath(), root.resolve(world.worldName()));
    }
    @Test void normalizesTemplatePathsWithinBoundary() throws Exception {
        assertEquals(template, files.validateTemplate(TestSupport.map(template.resolve("../city"))));
    }
    @Test void rejectsTraversalAndRuntimeAsTemplate() {
        assertThrows(IOException.class, () -> files.validateTemplate(TestSupport.map(data.resolve("../../world"))));
        assertThrows(IOException.class, () -> files.validateTemplate(TestSupport.map(runtime)));
    }
    @Test void rejectsMissingOrBrokenLevelData() throws Exception {
        Files.delete(template.resolve("level.dat"));
        assertThrows(IOException.class, () -> files.copy(id, "solo", map));
        Files.writeString(template.resolve("level.dat"), "not nbt");
        assertThrows(IOException.class, () -> files.copy(id, "solo", map));
        assertFalse(Files.exists(files.descriptor(id, "solo", map).runtimePath()));
    }
    @Test void rejectsMissingDirectoryAndRuntimeMarkerInTemplate() throws Exception {
        assertThrows(IOException.class, () -> files.validateTemplate(TestSupport.map(template.resolve("missing"))));
        Files.writeString(template.resolve(WorldFiles.MARKER), "sessionId=wrong");
        assertThrows(IOException.class, () -> files.copy(id, "solo", map));
    }
    @Test void runtimeRootCannotOverlapTemplateLobbyOrPluginRoot() {
        assertThrows(IOException.class, () -> new WorldFiles(data, data, root, List.of(template), List.of(lobby)));
        assertThrows(IOException.class, () -> new WorldFiles(data, template, root, List.of(template), List.of(lobby)));
        assertThrows(IOException.class, () -> new WorldFiles(data, runtime, root, List.of(template), List.of(data)));
        assertThrows(IOException.class, () -> new WorldFiles(data, root.resolve("outside"), root, List.of(template), List.of(lobby)));
    }
    @Test void rejectsRootTemplateLobbyAndArbitraryDirectoryDeletion() throws Exception {
        for (Path forbidden : List.of(runtime, template, lobby, data, root, root.resolve("arbitrary"))) {
            var malicious = new GameWorld(id, "solo", "unsafe", forbidden, map);
            assertThrows(IOException.class, () -> files.delete(malicious, true), forbidden.toString());
        }
        assertTrue(Files.exists(template.resolve("level.dat")));
    }
    @Test void rejectsMissingMarker() throws Exception {
        var world = files.copy(id, "solo", map); Files.delete(world.runtimePath().resolve(WorldFiles.MARKER));
        assertThrows(IOException.class, () -> files.delete(world, true));
        assertTrue(Files.exists(world.runtimePath().resolve("level.dat")));
    }
    @Test void rejectsMismatchedSessionMarker() throws Exception {
        var world = files.copy(id, "solo", map);
        Path marker = world.runtimePath().resolve(WorldFiles.MARKER);
        Files.writeString(marker, Files.readString(marker).replace(id.toString(), UUID.randomUUID().toString()));
        assertThrows(IOException.class, () -> files.delete(world, true));
        assertTrue(Files.exists(world.runtimePath().resolve("level.dat")));
    }
    @Test void refusesDeleteWithoutUnloadConfirmation() throws Exception {
        var world = files.copy(id, "solo", map);
        assertThrows(IOException.class, () -> files.delete(world, false));
        assertTrue(Files.exists(world.runtimePath()));
    }
    @Test void cleanupIsIsolatedAndIdempotent() throws Exception {
        var first = files.copy(id, "solo", map); var second = files.copy(UUID.randomUUID(), "squad", map);
        files.delete(first, true); files.delete(first, true);
        assertFalse(Files.exists(first.runtimePath()));
        assertTrue(Files.exists(second.runtimePath().resolve(WorldFiles.MARKER)));
        assertTrue(Files.exists(template.resolve("level.dat"))); assertTrue(Files.exists(lobby));
    }
    @Test void collisionDoesNotOverwriteExistingRuntime() throws Exception {
        var world = files.copy(id, "solo", map);
        assertThrows(FileAlreadyExistsException.class, () -> files.copy(id, "solo", map));
        assertTrue(Files.exists(world.runtimePath().resolve(WorldFiles.MARKER)));
    }
    @Test void partialCopyFailureCleansOnlyOwnedClone() throws Exception {
        int[] copies = {0};
        var faulty = new WorldFiles(data, runtime, root, List.of(template), List.of(lobby), (source, target) -> {
            if (++copies[0] == 2) throw new IOException("injected disk failure");
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS);
        });
        assertThrows(IOException.class, () -> faulty.copy(id, "solo", map));
        assertFalse(Files.exists(faulty.descriptor(id, "solo", map).runtimePath()));
        assertTrue(Files.exists(template.resolve("level.dat")));
    }
    @Test void copyRefusesSymlinkOrJunctionEvenInsideExcludedDirectory() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "keep");
        Path link = template.resolve("playerdata/link");
        link(link, outside);
        try {
            assertThrows(IOException.class, () -> files.copy(id, "solo", map));
            assertEquals("keep", Files.readString(outside.resolve("sentinel")));
        } finally { Files.delete(link); }
    }
    @Test void deletionRefusesLinkWithoutTouchingOutsideOrCloneContents() throws Exception {
        var world = files.copy(id, "solo", map);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "keep");
        Path link = world.runtimePath().resolve("escape");
        link(link, outside);
        try {
            assertThrows(IOException.class, () -> files.delete(world, true));
            assertEquals("keep", Files.readString(outside.resolve("sentinel")));
            assertTrue(Files.exists(world.runtimePath().resolve("level.dat")));
            assertTrue(Files.exists(world.runtimePath().resolve(WorldFiles.MARKER)));
        } finally { Files.delete(link); }
    }
    @Test void rootLinkIsRejected() throws Exception {
        Path other = Files.createDirectory(root.resolve("other"));
        Files.createDirectories(runtime.getParent()); link(runtime, other);
        try {
            assertThrows(IOException.class, () -> new WorldFiles(data, runtime, root, List.of(template), List.of(lobby)));
        } finally { Files.delete(runtime); }
    }
    private void link(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException exception) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes());
                assertEquals(0, process.waitFor(), "Cannot create test junction: " + output);
            } else throw exception;
        }
    }
}

