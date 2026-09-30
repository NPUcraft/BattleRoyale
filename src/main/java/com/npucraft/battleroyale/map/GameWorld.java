package com.npucraft.battleroyale.map;
import com.npucraft.battleroyale.util.Checks;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
/** Exact runtime resource identity. worldName is the checked Bukkit name derived from the owned dimension key. */
public record GameWorld(UUID sessionId, String roomId, String worldName, Path runtimePath, MapTemplate template, long expectedSeed) {
    public GameWorld(UUID sessionId, String roomId, String worldName, Path runtimePath, MapTemplate template) {
        this(sessionId, roomId, worldName, runtimePath, template, 0L);
    }
    public GameWorld {
        Objects.requireNonNull(sessionId); Checks.text(roomId, "roomId"); Checks.text(worldName, "worldName");
        Objects.requireNonNull(runtimePath); Objects.requireNonNull(template);
    }
}

