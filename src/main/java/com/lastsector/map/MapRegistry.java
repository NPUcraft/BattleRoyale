package com.lastsector.map;
import java.util.*;
/** Owner-thread registry of template descriptors; never operates on world files. */
public final class MapRegistry {
    private final Map<String, MapTemplate> maps = new LinkedHashMap<>();
    /** Registers metadata only; duplicate ids fail without replacing the original. */
    public void register(MapTemplate map) {
        Objects.requireNonNull(map, "map");
        if (maps.putIfAbsent(map.id(), map) != null) throw new IllegalArgumentException("Duplicate map: " + map.id());
    }
    /** Finds a template, returning empty for unknown ids. */
    public Optional<MapTemplate> find(String id) { return Optional.ofNullable(maps.get(id)); }
    /** Returns an immutable snapshot in registration order. */
    public List<MapTemplate> all() { return List.copyOf(maps.values()); }
}
