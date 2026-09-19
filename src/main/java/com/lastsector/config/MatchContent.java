package com.lastsector.config;
import com.lastsector.loadout.LoadoutDefinition;
import com.lastsector.loot.*;
import java.util.Map;
public record MatchContent(Map<String, LoadoutDefinition> loadouts, Map<String, LootTable> tables, Map<String, MapLoot> maps) {
    public MatchContent { loadouts = Map.copyOf(loadouts); tables = Map.copyOf(tables); maps = Map.copyOf(maps); }
}
