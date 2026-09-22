package com.npucraft.lastsector.admin;

import java.util.*;

/** Owner-thread leases; token identity prevents stale async callbacks releasing a successor. */
public final class MapMaintenanceLocks {
  public enum Kind {
    EDITING,
    VALIDATING_DEEP,
    PREGENERATING
  }

  public record Lease(String map, UUID token, UUID owner, Kind kind) {}

  private final Map<String, Lease> leases = new HashMap<>();

  public Lease acquire(String map, UUID owner, Kind kind) {
    var value = new Lease(map, UUID.randomUUID(), owner, kind);
    if (leases.putIfAbsent(map, value) != null)
      throw new IllegalStateException("Map is under maintenance: " + map);
    return value;
  }

  public boolean current(Lease lease) {
    return lease.equals(leases.get(lease.map()));
  }

  public void release(Lease lease) {
    leases.remove(lease.map(), lease);
  }

  public boolean busy(String map) {
    return leases.containsKey(map);
  }

  public List<Lease> all() {
    return List.copyOf(leases.values());
  }
}
