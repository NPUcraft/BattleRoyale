package com.npucraft.lastsector.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.npucraft.lastsector.map.MapTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * One atomic authority file covers area, loot and spectator; bounded backups precede replacement.
 */
public final class MapMetadataStore {
  private final Path root;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  public MapMetadataStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path file(String id) {
    if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Unsafe map id");
    return root.resolve("map-data").resolve(id).resolve("metadata.json");
  }

  public Optional<MapMetadata> read(String id) throws IOException {
    Path p = file(id);
    AtomicFiles.safe(p);
    if (!Files.exists(p)) return Optional.empty();
    if (Files.size(p) > 4 * 1024 * 1024) throw new IOException("Map metadata exceeds 4 MiB");
    try {
      return Optional.of(
          Objects.requireNonNull(gson.fromJson(Files.readString(p), MapMetadata.class)));
    } catch (RuntimeException e) {
      throw new IOException("Invalid map metadata: " + id, e);
    }
  }

  public MapTemplate overlay(MapTemplate map) throws IOException {
    return read(map.id()).map(m -> m.apply(map)).orElse(map);
  }

  public MapMetadata save(String id, MapMetadata draft, long expectedRevision) throws IOException {
    var old = read(id);
    long actual = old.map(MapMetadata::revision).orElse(0L);
    if (actual != expectedRevision) throw new IOException("Metadata revision conflict");
    var next =
        new MapMetadata(
            1,
            Math.addExact(actual, 1),
            draft.displayName(),
            draft.playableArea(),
            draft.loot(),
            draft.spectator());
    Path p = file(id), backups = p.getParent().resolve("backups");
    AtomicFiles.safe(backups);
    Files.createDirectories(backups);
    if (Files.exists(p))
      AtomicFiles.write(
          backups.resolve("metadata-" + actual + "-" + UUID.randomUUID() + ".json"),
          Files.readAllBytes(p));
    else {
      Path legacy = p.resolveSibling("loot.yml");
      if (Files.exists(legacy)) {
        AtomicFiles.safe(legacy);
        AtomicFiles.write(
            backups.resolve("legacy-loot-" + UUID.randomUUID() + ".yml"),
            Files.readAllBytes(legacy));
      }
    }
    AtomicFiles.write(p, gson.toJson(next).getBytes(StandardCharsets.UTF_8));
    // Retention is best-effort after publication; a failed prune cannot make a successful save look
    // failed.
    try (var entries = Files.list(backups)) {
      var files =
          entries
              .filter(
                  f ->
                      f.getFileName()
                          .toString()
                          .matches("(metadata|legacy-loot)-[a-zA-Z0-9-]+\\.(json|yml)"))
              .sorted(
                  Comparator.comparingLong(
                          (Path f) -> {
                            try {
                              return Files.getLastModifiedTime(f).toMillis();
                            } catch (IOException e) {
                              return Long.MAX_VALUE;
                            }
                          })
                      .reversed())
              .toList();
      for (int i = 5; i < files.size(); i++) {
        AtomicFiles.safe(files.get(i));
        Files.delete(files.get(i));
      }
    } catch (IOException ignored) {
    }
    return next;
  }
}
