package com.npucraft.lastsector.admin;

import com.npucraft.lastsector.config.ConfigurationSnapshot;
import com.npucraft.lastsector.map.*;
import java.util.*;

/** Pure metadata checks plus separately requested filesystem validation; no implicit world load. */
public final class MapValidationService {
  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }

  public record Finding(Severity severity, String message) {}

  public record Report(String map, long revision, java.time.Instant time, List<Finding> findings) {
    public Report {
      findings = List.copyOf(findings);
    }

    public boolean valid() {
      return findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
    }

    public String text() {
      return "Map: "
          + map
          + " revision="
          + revision
          + " time="
          + time
          + "\n"
          + findings.stream()
              .map(f -> f.severity() + ": " + f.message())
              .collect(java.util.stream.Collectors.joining("\n"));
    }
  }

  public Report metadata(
      MapTemplate map, MapMetadata data, ConfigurationSnapshot configuration, Set<String> tables) {
    var found = new ArrayList<Finding>();
    var area = data.playableArea();
    for (var room : configuration.rooms())
      if (room.mapPool().contains(map.id())) {
        var zone =
            configuration.zoneProfiles().stream()
                .filter(z -> z.id().equals(room.zoneProfileId()))
                .findFirst()
                .orElseThrow();
        double size = zone.largestReachableHalfSize(room.maxPlayers()) * 2;
        if (area.maxX() - area.minX() < size || area.maxZ() - area.minZ() < size)
          error(found, "Playable area cannot contain InitialZone for room " + room.id());
      }
    Set<String> ids = new HashSet<>(), blocks = new HashSet<>();
    for (var p : data.loot().containers()) {
      if (!ids.add(p.id())) error(found, "Duplicate id: " + p.id());
      if (!blocks.add(p.x() + ":" + p.y() + ":" + p.z()))
        error(found, "Duplicate container block: " + p.id());
      if (!tables.contains(p.table())) error(found, "Unknown LootTable: " + p.table());
      if (!area.contains(p.x(), p.z()) || p.y() < -2032 || p.y() > 2031)
        error(found, "Container outside bounds: " + p.id());
    }
    for (var a : data.loot().areas()) {
      if (!ids.add(a.id())) error(found, "Duplicate id: " + a.id());
      if (!tables.contains(a.table())) error(found, "Unknown LootTable: " + a.table());
      if (!area.contains(a.minX(), a.minZ())
          || !area.contains(a.maxX(), a.maxZ())
          || a.minY() < -2032
          || a.maxY() > 2031) error(found, "Ground area outside bounds: " + a.id());
    }
    if (data.loot().containers().size() > 1024 || data.loot().areas().size() > 128)
      error(found, "Metadata limit: 1024 containers / 128 areas");
    if (data.spectator() != null && !area.contains(data.spectator().x(), data.spectator().z()))
      error(found, "Spectator position outside playable area");
    if (data.spectator() == null)
      found.add(new Finding(Severity.INFO, "Spectator fallback uses zone/world spawn"));
    if (found.isEmpty()) found.add(new Finding(Severity.INFO, "Metadata valid"));
    return new Report(map.id(), data.revision(), java.time.Instant.now(), found);
  }

  public Report files(Report report, MapTemplate map, WorldFiles files) {
    var found = new ArrayList<>(report.findings());
    try {
      files.validateTemplate(map);
    } catch (Exception e) {
      error(found, "Template validation: " + e.getMessage());
    }
    return new Report(report.map(), report.revision(), report.time(), found);
  }

  private static void error(List<Finding> findings, String message) {
    findings.add(new Finding(Severity.ERROR, message));
  }
}
