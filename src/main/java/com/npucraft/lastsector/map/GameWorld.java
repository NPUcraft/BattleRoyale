package com.npucraft.lastsector.map;
import com.npucraft.lastsector.util.Checks;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
/** Exact runtime resource identity. worldName is a checked world-container-relative name. */
public record GameWorld(UUID sessionId, String roomId, String worldName, Path runtimePath, MapTemplate template, long expectedSeed) {
    public GameWorld(UUID sessionId, String roomId, String worldName, Path runtimePath, MapTemplate template) {
        this(sessionId, roomId, worldName, runtimePath, template, 0L);
    }
    public GameWorld {
        Objects.requireNonNull(sessionId); Checks.text(roomId, "roomId"); Checks.text(worldName, "worldName");
        Objects.requireNonNull(runtimePath); Objects.requireNonNull(template);
    }
}

