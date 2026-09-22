package com.npucraft.lastsector.room;
import java.util.*;
/** Owner-thread registry of permanent definitions. Duplicate registration is rejected. */
public final class RoomManager {
    private final Map<String, RoomDefinition> rooms = new LinkedHashMap<>();
    /** Registers one definition; duplicate ids fail without replacing the original. */
    public void register(RoomDefinition room) {
        Objects.requireNonNull(room, "room");
        if (rooms.putIfAbsent(room.id(), room) != null) throw new IllegalArgumentException("Duplicate room: " + room.id());
    }
    /** Finds a definition, returning empty for unknown ids. */
    public Optional<RoomDefinition> find(String id) { return Optional.ofNullable(rooms.get(id)); }
    /** Returns an immutable snapshot in registration order. */
    public List<RoomDefinition> all() { return List.copyOf(rooms.values()); }
}
