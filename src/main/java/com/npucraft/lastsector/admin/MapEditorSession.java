package com.npucraft.lastsector.admin;

import com.npucraft.lastsector.loot.*;
import com.npucraft.lastsector.map.*;
import java.util.*;

/** Owner-thread working metadata, independent of GameSession and world block mutation. */
public final class MapEditorSession {
  private final UUID owner;
  private final MapMaintenanceLocks.Lease lease;
  private MapMetadata draft;

  public MapEditorSession(UUID owner, MapMaintenanceLocks.Lease lease, MapMetadata metadata) {
    this.owner = owner;
    this.lease = lease;
    draft = metadata;
  }

  public UUID owner() {
    return owner;
  }

  public MapMaintenanceLocks.Lease lease() {
    return lease;
  }

  public MapMetadata draft() {
    return draft;
  }

  public void published(MapMetadata value) {
    draft = value;
  }

  private void replace(
      String name, PlayableArea area, MapLoot loot, MapMetadata.SpectatorPosition spectator) {
    draft = new MapMetadata(1, draft.revision(), name, area, loot, spectator);
  }

  public void area(PlayableArea area) {
    replace(draft.displayName(), area, draft.loot(), draft.spectator());
  }

  public void name(String name) {
    replace(name, draft.playableArea(), draft.loot(), draft.spectator());
  }

  public void spectator(MapMetadata.SpectatorPosition position) {
    replace(draft.displayName(), draft.playableArea(), draft.loot(), position);
  }

  public String container(int x, int y, int z, String table) {
    var points = new ArrayList<>(draft.loot().containers());
    var existing = points.stream().filter(p -> p.x() == x && p.y() == y && p.z() == z).findFirst();
    String id = existing.map(ContainerLootPoint::id).orElse("container-" + UUID.randomUUID());
    existing.ifPresent(points::remove);
    points.add(new ContainerLootPoint(id, x, y, z, table));
    replace(
        draft.displayName(),
        draft.playableArea(),
        new MapLoot(points, draft.loot().areas()),
        draft.spectator());
    return id;
  }

  public void ground(LootArea area) {
    var areas = new ArrayList<>(draft.loot().areas());
    areas.removeIf(a -> a.id().equals(area.id()));
    areas.add(area);
    replace(
        draft.displayName(),
        draft.playableArea(),
        new MapLoot(draft.loot().containers(), areas),
        draft.spectator());
  }

  public void remove(String id, boolean confirmed) {
    if (!confirmed) throw new IllegalStateException("Explicit remove confirmation required");
    replace(
        draft.displayName(),
        draft.playableArea(),
        new MapLoot(
            draft.loot().containers().stream().filter(p -> !p.id().equals(id)).toList(),
            draft.loot().areas().stream().filter(a -> !a.id().equals(id)).toList()),
        draft.spectator());
  }
}
