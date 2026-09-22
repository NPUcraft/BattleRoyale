package com.npucraft.lastsector.admin;

import com.google.gson.annotations.SerializedName;
import com.npucraft.lastsector.loot.*;
import com.npucraft.lastsector.map.*;
import java.util.*;

/** Complete, immutable editor publication. Never mutates a running GameSession. */
public record MapMetadata(
    @SerializedName("format-version") int formatVersion,
    long revision,
    String displayName,
    PlayableArea playableArea,
    MapLoot loot,
    SpectatorPosition spectator) {
  public record SpectatorPosition(double x, double y, double z, float yaw, float pitch) {
    public SpectatorPosition {
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || !Double.isFinite(z)
          || !Float.isFinite(yaw)
          || !Float.isFinite(pitch)
          || y < -2032
          || y > 2031
          || pitch < -90
          || pitch > 90) throw new IllegalArgumentException("Invalid spectator position");
    }
  }

  public MapMetadata {
    if (formatVersion != 1 || revision < 0 || displayName == null || displayName.isBlank())
      throw new IllegalArgumentException("Unsupported map metadata/version");
    Objects.requireNonNull(playableArea);
    Objects.requireNonNull(loot);
  }

  public MapTemplate apply(MapTemplate base) {
    return new MapTemplate(base.id(), displayName, base.templatePath(), playableArea, this);
  }

  public static MapMetadata initial(MapTemplate map, MapLoot loot) {
    return map.metadata() != null
        ? map.metadata()
        : new MapMetadata(1, 0, map.displayName(), map.playableArea(), loot, null);
  }
}
