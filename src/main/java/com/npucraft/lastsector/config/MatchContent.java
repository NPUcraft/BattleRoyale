package com.npucraft.lastsector.config;
import com.npucraft.lastsector.loadout.LoadoutDefinition;
import com.npucraft.lastsector.loot.*;
import java.util.Map;
public record MatchContent(Map<String, LoadoutDefinition> loadouts, Map<String, LootTable> tables, Map<String, MapLoot> maps,Map<String,String> mapErrors) {
    public MatchContent(Map<String,LoadoutDefinition> loadouts,Map<String,LootTable> tables,Map<String,MapLoot> maps){this(loadouts,tables,maps,Map.of());}
    public MatchContent { loadouts = Map.copyOf(loadouts); tables = Map.copyOf(tables); maps = Map.copyOf(maps);mapErrors=Map.copyOf(mapErrors); }
}
