package com.npucraft.battleroyale.config;

import com.npucraft.battleroyale.admin.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Legacy v1 -> v2 is additive only. Preflight the complete set before any write. */
public final class ConfigMigrationService {
  public static final int CURRENT = 2;
  public static final List<String> FILES =
      List.of(
          "config.yml",
          "rooms.yml",
          "maps.yml",
          "zones.yml",
          "loadouts.yml",
          "loot-tables.yml",
          "lobby.yml",
          "ranking.yml",
          "cosmetics.yml");
  private final Path root;

  public ConfigMigrationService(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public static int version(YamlConfiguration yaml) {
    Object raw = yaml.get("config-version", 1);
    if (!(raw instanceof Integer n) || n < 1 || n > CURRENT)
      throw new IllegalArgumentException("Unsupported config-version: " + raw);
    return n;
  }

  public List<String> migrate() throws IOException {
    var pending = new LinkedHashMap<Path, byte[]>();
    for (String name : FILES) {
      Path file = root.resolve(name);
      if (!Files.exists(file)) continue;
      AtomicFiles.safe(file);
      byte[] bytes = Files.readAllBytes(file);
      var yaml = new YamlConfiguration();
      try {
        yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new IOException("Invalid configuration: " + name, e);
      }
      if (version(yaml) == 1) pending.put(file, bytes);
    }
    if (pending.isEmpty()) return List.of();
    Path backup =
        root.resolve("config-backups")
            .resolve(java.time.Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
    AtomicFiles.safe(backup);
    Files.createDirectories(backup);
    for (var e : pending.entrySet())
      AtomicFiles.write(backup.resolve(e.getKey().getFileName()), e.getValue());
    var written = new ArrayList<Path>();
    try {
      for (var e : pending.entrySet()) {
        String original = new String(e.getValue(), StandardCharsets.UTF_8);
        var yaml = new YamlConfiguration();
        yaml.loadFromString(original);
        yaml.set("config-version", CURRENT);
        AtomicFiles.write(e.getKey(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        written.add(e.getKey());
      }
    } catch (Exception failure) {
      for (Path file : written)
        try {
          AtomicFiles.write(file, pending.get(file));
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      throw new IOException(
          "Configuration migration failed; originals backed up in " + backup, failure);
    }
    return pending.keySet().stream().map(p -> p.getFileName().toString()).toList();
  }

  public void validateVersions() throws IOException {
    for (String name : FILES) {
      Path p = root.resolve(name);
      if (!Files.exists(p)) continue;
      AtomicFiles.safe(p);
      var yaml = new YamlConfiguration();
      try {
        yaml.load(p.toFile());
        version(yaml);
      } catch (Exception e) {
        throw new IOException("Invalid configuration version: " + name, e);
      }
    }
  }
}
