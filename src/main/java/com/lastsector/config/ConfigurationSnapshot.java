package com.lastsector.config;
import com.lastsector.room.RoomDefinition;
import com.lastsector.map.MapTemplate;
import com.lastsector.zone.ZoneProfile;
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

