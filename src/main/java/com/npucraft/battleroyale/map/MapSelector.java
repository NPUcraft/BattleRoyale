package com.npucraft.battleroyale.map;
import com.npucraft.battleroyale.room.RoomDefinition;
import java.util.List;
import java.util.random.RandomGenerator;
/** Injected selection strategy. One call per session preparation. */
@FunctionalInterface
public interface MapSelector {
    MapTemplate select(RoomDefinition room, List<MapTemplate> candidates);
    static MapSelector random(RandomGenerator random) {
        return (room, candidates) -> {
            var pool = candidates.stream().filter(map -> room.mapPool().contains(map.id())).toList();
            if (pool.isEmpty()) throw new IllegalStateException("No maps in room pool: " + room.id());
            return pool.get(random.nextInt(pool.size()));
        };
    }
}

