package com.npucraft.battleroyale.config;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.map.MapTemplate;
import com.npucraft.battleroyale.zone.ZoneProfile;
import java.util.List;
import java.util.Objects;
/** Fully validated immutable configuration, published only after all four files succeed. */
public record ConfigurationSnapshot(PluginSettings settings, List<RoomDefinition> rooms,
                                    List<MapTemplate> maps, List<ZoneProfile> zoneProfiles) {
    public ConfigurationSnapshot {
        Objects.requireNonNull(settings);
        rooms = List.copyOf(rooms); maps = List.copyOf(maps); zoneProfiles = List.copyOf(zoneProfiles);
    }
}

