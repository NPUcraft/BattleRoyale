package com.npucraft.battleroyale.config;
import com.npucraft.battleroyale.loadout.LoadoutDefinition;
import com.npucraft.battleroyale.loot.*;
import java.util.Map;
public record MatchContent(Map<String, LoadoutDefinition> loadouts, Map<String, LootTable> tables, Map<String, MapLoot> maps,Map<String,String> mapErrors,AutoContainerLootSettings autoContainers,AirdropSettings airdrops,MobLootSettings mobLoot,HorseSettings horses,LootRegionQualitySettings regionQuality) {
    public MatchContent(Map<String,LoadoutDefinition> loadouts,Map<String,LootTable> tables,Map<String,MapLoot> maps,Map<String,String> mapErrors,AutoContainerLootSettings autoContainers,AirdropSettings airdrops,MobLootSettings mobLoot,HorseSettings horses){this(loadouts,tables,maps,mapErrors,autoContainers,airdrops,mobLoot,horses,LootRegionQualitySettings.DISABLED);}
    public MatchContent(Map<String,LoadoutDefinition> loadouts,Map<String,LootTable> tables,Map<String,MapLoot> maps,Map<String,String> mapErrors,AutoContainerLootSettings autoContainers,AirdropSettings airdrops,MobLootSettings mobLoot){this(loadouts,tables,maps,mapErrors,autoContainers,airdrops,mobLoot,HorseSettings.DEFAULT);}
    public MatchContent(Map<String,LoadoutDefinition> loadouts,Map<String,LootTable> tables,Map<String,MapLoot> maps,Map<String,String> mapErrors,AutoContainerLootSettings autoContainers,AirdropSettings airdrops){this(loadouts,tables,maps,mapErrors,autoContainers,airdrops,MobLootSettings.DEFAULT);}
    public MatchContent(Map<String,LoadoutDefinition> loadouts,Map<String,LootTable> tables,Map<String,MapLoot> maps,Map<String,String> mapErrors){this(loadouts,tables,maps,mapErrors,AutoContainerLootSettings.DEFAULT,AirdropSettings.DEFAULT);}
    public MatchContent(Map<String,LoadoutDefinition> loadouts,Map<String,LootTable> tables,Map<String,MapLoot> maps){this(loadouts,tables,maps,Map.of());}
    public MatchContent { loadouts = Map.copyOf(loadouts); tables = Map.copyOf(tables); maps = Map.copyOf(maps);mapErrors=Map.copyOf(mapErrors);java.util.Objects.requireNonNull(autoContainers);java.util.Objects.requireNonNull(airdrops);java.util.Objects.requireNonNull(mobLoot);java.util.Objects.requireNonNull(horses);java.util.Objects.requireNonNull(regionQuality);regionQuality.validate(tables); }
}
