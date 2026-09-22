package com.npucraft.lastsector.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.npucraft.lastsector.*;
import com.npucraft.lastsector.config.*;
import com.npucraft.lastsector.loot.*;
import com.npucraft.lastsector.map.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AdministrationTest {
  @TempDir Path root;

  MapMetadata metadata() {
    return new MapMetadata(
        1,
        0,
        "City",
        new PlayableArea(-600, 600, -600, 600),
        new MapLoot(List.of(), List.of()),
        null);
  }

  @Test
  void leasesExcludeSameMapAndIgnoreStaleRelease() {
    var locks = new MapMaintenanceLocks();
    var first = locks.acquire("city", UUID.randomUUID(), MapMaintenanceLocks.Kind.EDITING);
    assertThrows(
        IllegalStateException.class,
        () -> locks.acquire("city", UUID.randomUUID(), MapMaintenanceLocks.Kind.PREGENERATING));
    locks.acquire("desert", UUID.randomUUID(), MapMaintenanceLocks.Kind.EDITING);
    locks.release(first);
    var next = locks.acquire("city", UUID.randomUUID(), MapMaintenanceLocks.Kind.VALIDATING_DEEP);
    locks.release(first);
    assertTrue(locks.current(next));
  }

  @Test
  void metadataAtomicRevisionBackupsAndRunningSnapshot() throws Exception {
    var store = new MapMetadataStore(root);
    var original = metadata();
    var sessionMap = original.apply(TestSupport.map(root.resolve("template")));
    var current = original;
    for (int i = 0; i < 9; i++) current = store.save("city", current, current.revision());
    assertEquals(9, store.read("city").orElseThrow().revision());
    assertEquals(0, sessionMap.metadataRevision());
    try (var files = Files.list(root.resolve("map-data/city/backups"))) {
      assertEquals(5, files.count());
    }
    assertThrows(IOException.class, () -> store.save("city", original, 0));
    assertThrows(IllegalArgumentException.class, () -> store.file("../world"));
  }

  @Test
  void corruptAndNewerMetadataFailWithoutOverwrite() throws Exception {
    var store = new MapMetadataStore(root);
    store.save("city", metadata(), 0);
    var p = store.file("city");
    var corrupt = Files.readString(p).replace("\"format-version\": 1", "\"format-version\": 999");
    Files.writeString(p, corrupt);
    assertThrows(IOException.class, () -> store.read("city"));
    assertEquals(corrupt, Files.readString(p));
  }

  @Test
  void editorUpdatesContainerIdentityAndRequiresRemoveConfirmation() {
    var locks = new MapMaintenanceLocks();
    var owner = UUID.randomUUID();
    var editor =
        new MapEditorSession(
            owner, locks.acquire("city", owner, MapMaintenanceLocks.Kind.EDITING), metadata());
    String id = editor.container(1, 64, 2, "common");
    assertEquals(id, editor.container(1, 64, 2, "rare"));
    assertEquals(1, editor.draft().loot().containers().size());
    assertThrows(IllegalStateException.class, () -> editor.remove(id, false));
    editor.remove(id, true);
    assertTrue(editor.draft().loot().containers().isEmpty());
  }

  @Test
  void metricsHaveBoundedRollingWindow() {
    var metrics = new PerformanceMetricsService();
    for (int i = 1; i <= 10000; i++) metrics.record(PerformanceMetricsService.Timer.ZONE_TICK, i);
    var t = metrics.timings().get(PerformanceMetricsService.Timer.ZONE_TICK);
    assertEquals(10000, t.count());
    assertEquals(10000, t.maxNanos());
    assertEquals((9873 + 10000) / 2.0, t.rollingAverageNanos());
    assertThrows(
        IllegalArgumentException.class,
        () -> metrics.record(PerformanceMetricsService.Timer.DB_WRITE, -1));
  }

  @Test
  void redactionIsRecursiveAndBundleDoesNotReadArbitraryFiles() throws Exception {
    var original =
        Map.of(
            "mysql",
            Map.of(
                "password",
                "unique-secret-password",
                "username",
                "private-user",
                "url",
                "jdbc:mysql://host/db?password=x"),
            "nested",
            List.of(Map.of("api-token", "hidden-token")),
            "safe",
            12);
    String redacted = new com.google.gson.Gson().toJson(ConfigRedactor.redact(original));
    assertFalse(redacted.contains("unique-secret-password"));
    assertFalse(redacted.contains("hidden-token"));
    assertFalse(redacted.contains("private-user"));
    Files.writeString(root.resolve("inventory.txt"), "private inventory");
    Path zip = SupportBundle.write(root.resolve("support"), Map.of("config.txt", redacted));
    try (var archive = new java.util.zip.ZipFile(zip.toFile())) {
      assertEquals(1, archive.size());
      assertNotNull(archive.getEntry("config.txt"));
    }
    assertThrows(
        IOException.class,
        () -> SupportBundle.write(root.resolve("support"), Map.of("../escape.txt", "bad")));
  }

  @Test
  void diagnosticsRetainsIndependentComponents() {
    var service = new DiagnosticsService();
    service.add(
        "broken",
        () -> {
          throw new IllegalStateException("secret should not leak");
        });
    service.add(
        "good",
        () -> new DiagnosticsService.Check("good", DiagnosticsService.Status.OK, "healthy"));
    var report = service.collect();
    assertEquals(2, report.checks().size());
    assertEquals(DiagnosticsService.Status.ERROR, report.overall());
    assertFalse(report.text().contains("secret"));
  }

  @Test
  void legacyMigrationBacksUpAndPreservesValues() throws Exception {
    String original = "# legacy comment\neconomy:\n  currency: coins\ncustom: [one, two]\n";
    Files.writeString(root.resolve("config.yml"), original);
    assertEquals(List.of("config.yml"), new ConfigMigrationService(root).migrate());
    var yaml =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            root.resolve("config.yml").toFile());
    assertEquals(2, yaml.getInt("config-version"));
    assertEquals(List.of("one", "two"), yaml.getStringList("custom"));
    try (var files = Files.walk(root.resolve("config-backups"))) {
      Path backup = files.filter(Files::isRegularFile).findFirst().orElseThrow();
      assertEquals(original, Files.readString(backup));
    }
    assertTrue(new ConfigMigrationService(root).migrate().isEmpty());
  }

  @Test
  void newerConfigPreflightDoesNotModifyAnyFile() throws Exception {
    Files.writeString(root.resolve("config.yml"), "debug: false\n");
    Files.writeString(root.resolve("rooms.yml"), "config-version: 999\nrooms: {}\n");
    assertThrows(IllegalArgumentException.class, () -> new ConfigMigrationService(root).migrate());
    assertEquals("debug: false\n", Files.readString(root.resolve("config.yml")));
    assertFalse(Files.exists(root.resolve("config-backups")));
  }

  @Test
  void maintenanceMarkerCannotRecoverAsGameAndCancelKeepsTemplate() throws Exception {
    Path data = root.resolve("plugins/LastSector"), source = data.resolve("maps/city");
    TestSupport.level(source.resolve("level.dat"), 42);
    var map = TestSupport.map(source);
    var files =
        new WorldFiles(data, data.resolve("maintenance-worlds"), root, List.of(source), List.of());
    byte[] before = Files.readAllBytes(source.resolve("level.dat"));
    var copy = files.copy(UUID.randomUUID(), "maintenance", map, "EDITOR", UUID.randomUUID());
    assertThrows(
        IOException.class,
        () ->
            files.recovery(
                copy.sessionId(),
                copy.roomId(),
                map,
                copy.worldName(),
                copy.runtimePath().getFileName().toString()));
    files.delete(copy, true);
    assertArrayEquals(before, Files.readAllBytes(source.resolve("level.dat")));
  }

  @Test
  void templateCommitRequiresConfirmationAndKeepsCompleteBackup() throws Exception {
    Path data = root.resolve("plugins/LastSector"), source = data.resolve("maps/city");
    TestSupport.level(source.resolve("level.dat"), 42);
    var map = TestSupport.map(source);
    var files =
        new WorldFiles(data, data.resolve("maintenance-worlds"), root, List.of(source), List.of());
    var copy = files.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID());
    Files.writeString(copy.runtimePath().resolve("new-region"), "generated");
    var commits = new TemplateCommitService(data);
    assertThrows(IOException.class, () -> commits.commit(map, copy, files, false));
    assertFalse(Files.exists(source.resolve("new-region")));
    Path backup = commits.commit(map, copy, files, true);
    assertTrue(Files.exists(backup.resolve("level.dat")));
    assertFalse(Files.exists(backup.resolve("new-region")));
    assertEquals("generated", Files.readString(source.resolve("new-region")));
    assertFalse(Files.exists(source.resolve(WorldFiles.MARKER)));
    files.validateTemplate(map);
  }

  @Test
  void interruptedSwapRollsBackOriginal() throws Exception {
    Path data = root.resolve("plugins/LastSector"), source = data.resolve("maps/city");
    TestSupport.level(source.resolve("level.dat"), 42);
    var map = TestSupport.map(source);
    var files =
        new WorldFiles(data, data.resolve("maintenance-worlds"), root, List.of(source), List.of());
    var copy = files.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID());
    Path backup = source.resolveSibling("city.backup-" + copy.sessionId());
    var p = new Properties();
    p.setProperty("target", data.relativize(source).toString());
    p.setProperty("source", data.relativize(copy.runtimePath()).toString());
    p.setProperty("backup", data.relativize(backup).toString());
    p.setProperty("token", copy.sessionId().toString());
    p.setProperty("state", "PREPARED");
    Path journal = data.resolve("maintenance-journals/city.properties");
    Files.createDirectories(journal.getParent());
    try (var out = Files.newOutputStream(journal)) {
      p.store(out, "fixture");
    }
    Files.move(source, backup);
    Files.move(copy.runtimePath(), source);
    new TemplateCommitService(data).recover(map);
    assertTrue(Files.exists(source.resolve("level.dat")));
    assertFalse(Files.exists(source.resolve(WorldFiles.MARKER)));
    assertTrue(Files.exists(copy.runtimePath().resolve(WorldFiles.MARKER)));
    files.delete(copy, true);
  }
}
