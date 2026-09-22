package com.npucraft.lastsector.admin;

import com.npucraft.lastsector.map.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Controlled same-filesystem replacement with a durable rollback journal. Worker-thread only. */
public final class TemplateCommitService {
  private final Path root;

  public TemplateCommitService(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path commit(MapTemplate template, GameWorld clone, WorldFiles files, boolean confirmed)
      throws IOException {
    if (!confirmed) throw new IOException("Explicit pregeneration commit confirmation required");
    files.validateTemplate(template);
    files.validateClone(clone);
    checkToken(clone.runtimePath(), clone.sessionId());
    long seed = LevelData.validate(clone.runtimePath().resolve("level.dat"));
    if (seed != clone.expectedSeed()) throw new IOException("Seed mismatch");
    Path target = template.templatePath().toAbsolutePath().normalize(),
        source = clone.runtimePath(),
        backup = target.resolveSibling(target.getFileName() + ".backup-" + clone.sessionId());
    Path journal = journal(template.id());
    AtomicFiles.safe(target);
    AtomicFiles.safe(source);
    AtomicFiles.safe(backup);
    if (Files.exists(backup) || Files.exists(journal))
      throw new IOException("Unresolved template commit/backup collision");
    var props = new Properties();
    props.setProperty("target", root.relativize(target).toString());
    props.setProperty("source", root.relativize(source).toString());
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
    Path target = resolve(p.getProperty("target")),
        source = resolve(p.getProperty("source")),
        backup = resolve(p.getProperty("backup"));
    UUID token = UUID.fromString(p.getProperty("token"));
    if (!target.equals(template.templatePath().toAbsolutePath().normalize())
        || !backup.equals(target.resolveSibling(target.getFileName() + ".backup-" + token))
        || !source.getFileName().toString().equals(WorldFiles.leafName("maintenance", token))
        || !source.getParent().equals(root.resolve("maintenance-worlds")))
      throw new IOException("Commit journal identity mismatch");
    if ("COMMITTED".equals(p.getProperty("state"))) {
      if (!Files.isDirectory(target) || !Files.isDirectory(backup))
        throw new IOException("Incomplete committed replacement");
      if (Files.exists(target.resolve(WorldFiles.MARKER))) checkToken(target, token);
      LevelData.validate(target.resolve("level.dat"));
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
        || !"MAINTENANCE".equals(p.getProperty("type")))
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
    p.store(out, "LastSector template replacement journal");
    AtomicFiles.write(file, out.toByteArray());
  }
}
