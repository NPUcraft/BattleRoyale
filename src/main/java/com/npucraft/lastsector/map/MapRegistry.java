package com.npucraft.lastsector.map;
import java.util.*;
/** Owner-thread registry of template descriptors; never operates on world files. */
public final class MapRegistry {
    private final Map<String,String> unavailable=new LinkedHashMap<>();
    public void unavailable(String id,String reason){unavailable.put(id,reason);}
    public void available(String id){unavailable.remove(id);}
    public boolean isAvailable(String id){return maps.containsKey(id)&&!unavailable.containsKey(id);}
    public String status(String id){return unavailable.getOrDefault(id,"AVAILABLE");}
    public void replace(MapTemplate map){if(!maps.containsKey(map.id()))throw new IllegalArgumentException("Unknown map");maps.put(map.id(),map);}
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
