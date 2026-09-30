package com.npucraft.battleroyale.map;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.admin.TemplateCommitService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PaperDimensionWorldFilesTest {
    @TempDir Path root;
    private Path data() throws IOException { return Files.createDirectories(root.resolve("plugins/BattleRoyale")); }
    private WorldFiles files(Path template, String namespace) throws IOException {
        return WorldFiles.paper262(data(), new RuntimeLayout(root.resolve("world"), namespace), List.of(template),
                List.of(root.resolve("world/dimensions/minecraft/overworld")));
    }
    private static void dimension(Path directory, long seed) throws IOException {
        Path file = directory.resolve(LevelData.WORLD_GEN_SETTINGS);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(4903);
            out.writeByte(10); out.writeUTF("data");
            out.writeByte(4); out.writeUTF("seed"); out.writeLong(seed);
            out.writeByte(10); out.writeUTF("dimensions");
            out.writeByte(10); out.writeUTF("minecraft:overworld");
            out.writeByte(8); out.writeUTF("type"); out.writeUTF("minecraft:overworld");
            out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(0);
        }
        Files.createDirectories(directory.resolve("region"));
        Files.writeString(directory.resolve("region/r.0.0.mca"), "terrain");
    }
    @Test void copiesStandaloneDimensionWithSeedButWithoutWorldOrPlayerIdentity() throws Exception {
        Path template = data().resolve("maps/wilderness"); dimension(template, 846219L);
        for (String excluded : List.of("data/paper/metadata.dat", "data/paper/metadata.dat_old", "players/data/player.dat", "uid.dat", "session.lock")) {
            Path file = template.resolve(excluded); Files.createDirectories(file.getParent()); Files.writeString(file, "private");
        }
        Files.writeString(template.resolve("data/paper/level_overrides.dat"), "retain settings");
        var map = TestSupport.map(template); var files = files(template, RuntimeLayout.GAME);
        var first = files.copy(UUID.randomUUID(), "solo", map);
        var second = files.copy(UUID.randomUUID(), "solo", map);
        assertEquals(846219L, first.expectedSeed());
        assertEquals(root.resolve("world/dimensions/battleroyale_game"), first.runtimePath().getParent());
        assertEquals("battleroyale_game_" + first.runtimePath().getFileName(), first.worldName());
        for (var world : List.of(first, second)) {
            assertFalse(Files.exists(world.runtimePath().resolve("data/paper/metadata.dat")));
            assertFalse(Files.exists(world.runtimePath().resolve("data/paper/metadata.dat_old")));
            assertFalse(Files.exists(world.runtimePath().resolve("players")));
            assertFalse(Files.exists(world.runtimePath().resolve("uid.dat")));
            assertEquals("retain settings", Files.readString(world.runtimePath().resolve("data/paper/level_overrides.dat")));
            assertEquals("terrain", Files.readString(world.runtimePath().resolve("region/r.0.0.mca")));
        }
        assertNotEquals(first.runtimePath(), second.runtimePath());
        files.delete(first, true);
        assertTrue(Files.exists(second.runtimePath().resolve(WorldFiles.MARKER)));
        assertTrue(Files.exists(template.resolve("players/data/player.dat")));
    }
    @Test void completeSaveExportsOnlyOverworldAndNeverCopiesGlobalPlayerData() throws Exception {
        Path template = data().resolve("maps/save");
        Path overworld = template.resolve("dimensions/minecraft/overworld"); dimension(overworld, -98765);
        Files.createDirectories(template.resolve("players/data")); Files.writeString(template.resolve("players/data/private.dat"), "original");
        dimension(template.resolve("dimensions/minecraft/the_nether"), 333);
        Files.writeString(template.resolve("level.dat"), "global save data is not a dimension");
        var files = files(template, RuntimeLayout.GAME); var map = TestSupport.map(template);
        assertEquals(overworld, files.validateTemplate(map));
        var world = files.copy(UUID.randomUUID(), "solo", map);
        assertEquals(-98765, world.expectedSeed());
        assertFalse(Files.exists(world.runtimePath().resolve("dimensions")));
        assertFalse(Files.exists(world.runtimePath().resolve("players")));
        assertFalse(Files.exists(world.runtimePath().resolve("level.dat")));
        assertEquals(-98765, LevelData.validateDimension(world.runtimePath()));
    }
    @Test void legacyTemplateAndLegacyRecoveryAreRefusedWithoutMigration() throws Exception {
        Path template = data().resolve("maps/legacy"); TestSupport.level(template.resolve("level.dat"), 42);
        byte[] before = Files.readAllBytes(template.resolve("level.dat"));
        var files = files(template, RuntimeLayout.GAME); var map = TestSupport.map(template);
        assertTrue(assertThrows(IOException.class, () -> files.copy(UUID.randomUUID(), "solo", map)).getMessage().contains("offline"));
        assertArrayEquals(before, Files.readAllBytes(template.resolve("level.dat")));
        dimension(template, 42);
        var world = files.copy(UUID.randomUUID(), "solo", map);
        assertThrows(IOException.class, () -> files.recovery(world.sessionId(), "solo", map,
                "plugins/BattleRoyale/runtime/" + world.runtimePath().getFileName(), world.runtimePath().getFileName().toString()));
        Path marker = world.runtimePath().resolve(WorldFiles.MARKER);
        Properties properties = new Properties(); try (var in = Files.newInputStream(marker)) { properties.load(in); }
        properties.remove("storageLayout"); try (var out = Files.newOutputStream(marker)) { properties.store(out, "legacy"); }
        assertThrows(IOException.class, () -> files.delete(world, true));
        assertTrue(files.ownedChildren(message -> {}).isEmpty());
    }
    @Test void namespacesRejectForeignTypesRootsAndForgedWorldDescriptors() throws Exception {
        Path template = data().resolve("maps/map"); dimension(template, 1);
        var map = TestSupport.map(template); var game = files(template, RuntimeLayout.GAME);
        var maintenance = files(template, RuntimeLayout.MAINTENANCE);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLayout(root.resolve("world"), "minecraft"));
        assertThrows(IOException.class, () -> game.copy(UUID.randomUUID(), "maintenance", map, "EDITOR", UUID.randomUUID()));
        assertThrows(IOException.class, () -> maintenance.copy(UUID.randomUUID(), "solo", map));
        var world = game.copy(UUID.randomUUID(), "solo", map);
        assertThrows(IOException.class, () -> maintenance.delete(world, true));
        for (Path forbidden : List.of(root.resolve("world"), root.resolve("world/dimensions"),
                root.resolve("world/dimensions/minecraft/overworld"), game.runtimeRoot(), data(), template))
            assertThrows(IOException.class, () -> game.delete(new GameWorld(world.sessionId(), "solo", world.worldName(), forbidden, map), true));
        assertTrue(Files.exists(world.runtimePath().resolve(WorldFiles.MARKER)));
        assertThrows(IOException.class, () -> WorldFiles.paper262(data(), new RuntimeLayout(root.resolve("world"), RuntimeLayout.GAME),
                List.of(template), List.of(root.resolve("world"))));
    }
    @Test void newRecoveryPreservesSavedWorldIdentityAndOnlyGameNamespaceCanRecover() throws Exception {
        Path template = data().resolve("maps/map"); dimension(template, 1); var map = TestSupport.map(template);
        var files = files(template, RuntimeLayout.GAME); var world = files.copy(UUID.randomUUID(), "solo", map);
        Path identity = world.runtimePath().resolve("data/paper/metadata.dat"); Files.createDirectories(identity.getParent()); Files.writeString(identity, "runtime-uuid");
        var recovered = files.recovery(world.sessionId(), "solo", map, world.worldName(), world.runtimePath().getFileName().toString());
        assertEquals(world, recovered); assertEquals("runtime-uuid", Files.readString(identity));
        var maintenance = files(template, RuntimeLayout.MAINTENANCE);
        var editor = maintenance.copy(UUID.randomUUID(), "maintenance", map, "EDITOR", UUID.randomUUID());
        assertThrows(IOException.class, () -> maintenance.recovery(editor.sessionId(), "maintenance", map, editor.worldName(), editor.runtimePath().getFileName().toString()));
    }
    @Test void modernCommitReplacesOnlyExportedDimensionAndKeepsBackup() throws Exception {
        Path template = data().resolve("maps/save"), original = template.resolve("dimensions/minecraft/overworld");
        dimension(original, 42); Files.writeString(template.resolve("level.dat"), "leave global data");
        var files = files(template, RuntimeLayout.MAINTENANCE); var map = TestSupport.map(template);
        var clone = files.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID());
        Files.writeString(clone.runtimePath().resolve("region/new.mca"), "generated");
        var commits = new TemplateCommitService(data(), files);
        assertThrows(IOException.class, () -> commits.commit(map, clone, files, false));
        Path backup = commits.commit(map, clone, files, true);
        assertEquals("leave global data", Files.readString(template.resolve("level.dat")));
        assertEquals("generated", Files.readString(original.resolve("region/new.mca")));
        assertFalse(Files.exists(original.resolve(WorldFiles.MARKER)));
        assertFalse(Files.exists(backup.resolve("region/new.mca")));
        assertEquals(42, LevelData.validateDimension(backup));
        files.validateTemplate(map);
    }
    @Test void modernInterruptedCommitRollsBackAcrossPluginAndDimensionRoots() throws Exception {
        Path template = data().resolve("maps/map"); dimension(template, 42); var map = TestSupport.map(template);
        var files = files(template, RuntimeLayout.MAINTENANCE);
        var clone = files.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID());
        Files.writeString(clone.runtimePath().resolve("region/new.mca"), "generated");
        Path backup = template.resolveSibling(template.getFileName() + ".backup-" + clone.sessionId());
        var properties = new Properties(); properties.setProperty("storageLayout", RuntimeLayout.STORAGE_ID);
        properties.setProperty("target", data().relativize(template).toString());
        properties.setProperty("backup", data().relativize(backup).toString());
        properties.setProperty("source", clone.runtimePath().getFileName().toString());
        properties.setProperty("token", clone.sessionId().toString()); properties.setProperty("state", "PREPARED");
        Path journal = data().resolve("maintenance-journals/city.properties"); Files.createDirectories(journal.getParent());
        try (var out = Files.newOutputStream(journal)) { properties.store(out, "crash fixture"); }
        Files.move(template, backup); Files.move(clone.runtimePath(), template);
        new TemplateCommitService(data(), files).recover(map);
        assertFalse(Files.exists(template.resolve("region/new.mca")));
        assertEquals("generated", Files.readString(clone.runtimePath().resolve("region/new.mca")));
        assertFalse(Files.exists(journal)); files.delete(clone, true); files.validateTemplate(map);
    }
    @Test void forgedJournalCannotMoveForeignDimension() throws Exception {
        Path template = data().resolve("maps/map"); dimension(template, 42); var map = TestSupport.map(template);
        var files = files(template, RuntimeLayout.MAINTENANCE);
        var token = UUID.randomUUID(); var properties = new Properties(); properties.setProperty("storageLayout", RuntimeLayout.STORAGE_ID);
        properties.setProperty("target", data().relativize(template).toString());
        properties.setProperty("backup", data().relativize(template.resolveSibling("map.backup-" + token)).toString());
        properties.setProperty("source", "../minecraft/overworld"); properties.setProperty("token", token.toString()); properties.setProperty("state", "PREPARED");
        Path journal = data().resolve("maintenance-journals/city.properties"); Files.createDirectories(journal.getParent());
        try (var out = Files.newOutputStream(journal)) { properties.store(out, "forged fixture"); }
        assertThrows(IOException.class, () -> new TemplateCommitService(data(), files).recover(map));
        assertTrue(Files.exists(journal)); assertEquals(42, LevelData.validateDimension(template));
    }
}
