package com.npucraft.battleroyale.admin;

import com.npucraft.battleroyale.map.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Controlled same-filesystem replacement with a durable rollback journal. Worker-thread only. */
public final class TemplateCommitService {
  private final Path root;
  private final WorldFiles worldFiles;

  public TemplateCommitService(Path root) { this(root, null); }

  public TemplateCommitService(Path root, WorldFiles worldFiles) {
    this.root = root.toAbsolutePath().normalize();
    this.worldFiles = worldFiles;
    if (worldFiles != null && worldFiles.dimensionStorage()
        && !worldFiles.runtimeRoot().getFileName().toString().equals(RuntimeLayout.MAINTENANCE))
      throw new IllegalArgumentException("Template commits require the maintenance namespace");
  }

  public Path commit(MapTemplate template, GameWorld clone, WorldFiles files, boolean confirmed)
      throws IOException {
    if (!confirmed) throw new IOException("Explicit pregeneration commit confirmation required");
    if (!clone.roomId().equals("maintenance") || !clone.template().id().equals(template.id())
        || !clone.template().templatePath().toAbsolutePath().normalize()
            .equals(template.templatePath().toAbsolutePath().normalize()))
      throw new IOException("Clone does not belong to this template maintenance operation");
    Path target = files.validateTemplate(template);
    if (files.dimensionStorage() && (worldFiles == null || !worldFiles.runtimeRoot().equals(files.runtimeRoot())))
      throw new IOException("Commit service does not own this maintenance namespace");
    files.validateClone(clone);
    checkToken(clone.runtimePath(), clone.sessionId());
    long seed = files.worldSeed(clone.runtimePath());
    if (seed != clone.expectedSeed()) throw new IOException("Seed mismatch");
    Path source = clone.runtimePath(),
        backup = target.resolveSibling(target.getFileName() + ".backup-" + clone.sessionId());
    Path journal = journal(template.id());
    AtomicFiles.safe(target);
    AtomicFiles.safe(source);
    AtomicFiles.safe(backup);
    if (Files.exists(backup) || Files.exists(journal))
      throw new IOException("Unresolved template commit/backup collision");
    var props = new Properties();
    props.setProperty("target", root.relativize(target).toString());
    if (files.dimensionStorage()) {
      props.setProperty("storageLayout", RuntimeLayout.STORAGE_ID);
      props.setProperty("source", source.getFileName().toString());
    } else props.setProperty("source", root.relativize(source).toString());
    props.setProperty("backup", root.relativize(backup).toString());
    props.setProperty("token", clone.sessionId().toString());
    props.setProperty("state", "PREPARED");
    write(journal, props);
    try {
      Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      props.setProperty("state", "COMMITTED");
      write(journal, props);
    } catch (IOException error) {
      try {
        recover(template);
      } catch (IOException rollback) {
        error.addSuppressed(rollback);
      }
      throw error;
    }
    // A committed journal permits removal of the clone marker in the new template on restart too.
    Files.delete(target.resolve(WorldFiles.MARKER));
    Files.delete(journal);
    return backup;
  }

  public void recover(MapTemplate template) throws IOException {
    Path j = journal(template.id());
    AtomicFiles.safe(j);
    if (!Files.exists(j)) return;
    if (Files.size(j) > 16384) throw new IOException("Oversized commit journal");
    var p = new Properties();
    try (var in = Files.newInputStream(j)) {
      p.load(in);
    }
    Path target = resolve(p.getProperty("target")), backup = resolve(p.getProperty("backup"));
    UUID token;
    try { token = UUID.fromString(p.getProperty("token")); }
    catch (RuntimeException invalid) { throw new IOException("Invalid journal token", invalid); }
    boolean modern = RuntimeLayout.STORAGE_ID.equals(p.getProperty("storageLayout"));
    if (p.containsKey("storageLayout") && !modern) throw new IOException("Unknown commit storage layout");
    if (modern != (worldFiles != null && worldFiles.dimensionStorage()))
      throw new IOException("Legacy/foreign maintenance journals require offline review; not migrated");
    Path maintenanceRoot = modern ? worldFiles.runtimeRoot() : root.resolve("maintenance-worlds");
    String leaf = WorldFiles.leafName("maintenance", token);
    Path source;
    if (modern) {
      if (!leaf.equals(p.getProperty("source"))) throw new IOException("Unsafe journal source identity");
      source = maintenanceRoot.resolve(leaf);
      AtomicFiles.safe(source);
    } else source = resolve(p.getProperty("source"));
    Path configured = template.templatePath().toAbsolutePath().normalize();
    boolean validTarget = target.equals(configured)
        || modern && target.equals(configured.resolve("dimensions/minecraft/overworld"));
    if (!validTarget || !backup.equals(target.resolveSibling(target.getFileName() + ".backup-" + token))
        || !source.getFileName().toString().equals(leaf) || !source.getParent().equals(maintenanceRoot))
      throw new IOException("Commit journal identity mismatch");
    if ("COMMITTED".equals(p.getProperty("state"))) {
      if (!Files.isDirectory(target) || !Files.isDirectory(backup))
        throw new IOException("Incomplete committed replacement");
      if (Files.exists(target.resolve(WorldFiles.MARKER))) checkToken(target, token);
      if (modern) LevelData.validateDimension(target);
      else LevelData.validate(target.resolve("level.dat"));
      Files.deleteIfExists(target.resolve(WorldFiles.MARKER));
      Files.delete(j);
      return;
    }
    if (!"PREPARED".equals(p.getProperty("state"))) throw new IOException("Unknown commit state");
    if (Files.exists(backup)) {
      if (Files.exists(target)) {
        checkToken(target, token);
        if (Files.exists(source)) throw new IOException("Rollback source occupied");
        Files.move(target, source, StandardCopyOption.ATOMIC_MOVE);
      }
      Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
    } else if (!Files.exists(target)) throw new IOException("Template and backup both absent");
    Files.delete(j);
  }

  private void checkToken(Path directory, UUID token) throws IOException {
    Path marker = directory.resolve(WorldFiles.MARKER);
    AtomicFiles.safe(marker);
    if (!Files.isRegularFile(marker) || Files.size(marker) > 16384)
      throw new IOException("Replacement marker absent");
    var p = new Properties();
    try (var in = Files.newInputStream(marker)) {
      p.load(in);
    }
    if (!token.toString().equals(p.getProperty("sessionId"))
        || !"MAINTENANCE".equals(p.getProperty("type"))
        || worldFiles != null && worldFiles.dimensionStorage()
            && (!RuntimeLayout.STORAGE_ID.equals(p.getProperty("storageLayout"))
                || !RuntimeLayout.MAINTENANCE.equals(p.getProperty("namespace"))))
      throw new IOException("Replacement ownership mismatch");
  }

  private Path journal(String id) {
    if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Unsafe map id");
    return root.resolve("maintenance-journals").resolve(id + ".properties");
  }

  private Path resolve(String relative) throws IOException {
    if (relative == null) throw new IOException("Missing journal path");
    Path p = root.resolve(relative).normalize();
    if (!p.startsWith(root) || p.equals(root)) throw new IOException("Unsafe journal path");
    AtomicFiles.safe(p);
    return p;
  }

  private static void write(Path file, Properties p) throws IOException {
    var out = new ByteArrayOutputStream();
    p.store(out, "BattleRoyale template replacement journal");
    AtomicFiles.write(file, out.toByteArray());
  }
}
