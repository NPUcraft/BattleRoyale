package com.npucraft.battleroyale.config;

import com.npucraft.battleroyale.loadout.*;
import com.npucraft.battleroyale.loot.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Strict M4 catalog loader. No publication until every room, item and map reference validates. */
public final class MatchContentLoader {
    private final Path directory;
    private final LootItemResolver<?> items;
    private final Consumer<StoredItem> validateItem;
    public MatchContentLoader(Path directory, LootItemResolver<?> items, Consumer<StoredItem> validateItem) {
        this.directory = directory; this.items = items; this.validateItem = validateItem;
    }
    public MatchContent load(ConfigurationSnapshot configuration) {return load(configuration,false);}
    public MatchContent load(ConfigurationSnapshot configuration,boolean isolateMapErrors) {
        Map<String, LoadoutDefinition> loadouts = new LinkedHashMap<>();
        ConfigurationSection root = section(read("loadouts.yml"), "loadouts");
        for (String id : root.getKeys(false)) {
            ConfigurationSection node = section(root, id);
            Map<Integer, StoredItem> slots = new LinkedHashMap<>();
            ConfigurationSection data = section(node, "slots");
            for (String key : data.getKeys(false)) {
                ConfigurationSection item = section(data, key);
                StoredItem stored = new StoredItem(text(item,"format"), integer(item,"version"), text(item,"data"));
                try { validateItem.accept(stored); }
                catch (RuntimeException failure) { throw new IllegalArgumentException("loadouts.yml: " + id + ".slots." + key + ": " + failure.getMessage(), failure); }
                if (slots.put(Integer.parseInt(key), stored) != null) throw new IllegalArgumentException("Duplicate loadout slot: " + key);
            }
            loadouts.put(id, new LoadoutDefinition(id, slots, node.contains("selected-hotbar-slot") ? integer(node,"selected-hotbar-slot") : 0));
        }
        configuration.rooms().forEach(room -> require(loadouts, room.loadoutId(), "room " + room.id() + " loadout"));
        Map<String, LootTable> tables = new LinkedHashMap<>();
        var lootConfig=read("loot-tables.yml");
        root = section(lootConfig, "loot-tables");
        for (String id : root.getKeys(false)) {
            ConfigurationSection node = section(root, id);
            List<LootTable.Entry> entries = new ArrayList<>();
            for (ConfigurationSection entry : list(node,"entries")) {
                String item = text(entry,"item"); items.validate(item);
                entries.add(new LootTable.Entry(item, whole(entry,"weight"), integer(entry,"min-amount"), integer(entry,"max-amount")));
            }
            tables.put(id, new LootTable(id, integer(node,"min-rolls"), integer(node,"max-rolls"), entries));
        }
        Map<String, MapLoot> maps = new LinkedHashMap<>();var mapErrors=new LinkedHashMap<String,String>();
        for (var map : configuration.maps()) {try{
            if(map.validationError()!=null)throw new IllegalArgumentException(map.validationError());
            var overlay=new com.npucraft.battleroyale.admin.MapMetadataStore(directory).read(map.id());if(overlay.isPresent()){var value=overlay.get();var report=new com.npucraft.battleroyale.admin.MapValidationService().metadata(value.apply(map),value,configuration,tables.keySet());if(!report.valid())throw new IllegalArgumentException(report.text());maps.put(map.id(),value.loot());continue;}
            if (!map.id().matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Unsafe map metadata id: " + map.id());
            ConfigurationSection metadata = read("map-data/" + map.id() + "/loot.yml");
            List<ContainerLootPoint> points = new ArrayList<>(); List<LootArea> areas = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (ConfigurationSection point : list(metadata,"containers")) {
                String id = unique(point,ids); String table = text(point,"loot-table"); require(tables,table,id);
                int x = integer(point,"x"), y = integer(point,"y"), z = integer(point,"z");
                if (!map.playableArea().contains(x,z) || y < -2032 || y > 2031) throw new IllegalArgumentException("Container outside map/world bounds: " + id);
                points.add(new ContainerLootPoint(id,x,y,z,table));
            }
            for (ConfigurationSection area : list(metadata,"areas")) {
                String id = unique(area,ids); String table = text(area,"loot-table"); require(tables,table,id);
                LootArea value = new LootArea(id,integer(area,"min-x"),integer(area,"max-x"),integer(area,"min-y"),integer(area,"max-y"),
                        integer(area,"min-z"),integer(area,"max-z"),table,number(area,"activation-chance"),integer(area,"min-spawns"),integer(area,"max-spawns"),integer(area,"max-attempts"));
                if (!map.playableArea().contains(value.minX(),value.minZ()) || !map.playableArea().contains(value.maxX(),value.maxZ())
                        || value.minY() < -2032 || value.maxY() > 2031) throw new IllegalArgumentException("Loot area outside map/world bounds: " + id);
                areas.add(value);
            }
            if (points.size() > 1024 || areas.size() > 128) throw new IllegalArgumentException("Map loot limit: 1024 containers / 128 areas");
            maps.put(map.id(), new MapLoot(points,areas));
            }catch(Exception e){if(!isolateMapErrors)throw new IllegalArgumentException("Map "+map.id()+": "+e.getMessage(),e);mapErrors.put(map.id(),e.getMessage());maps.put(map.id(),new MapLoot(List.of(),List.of()));}
        }
        String defaultTable=tables.containsKey("basic")?"basic":tables.keySet().stream().findFirst().orElse("basic");
        var auto=new AutoContainerLootSettings(!tables.isEmpty(),defaultTable,.9,2,5,16);
        if(lootConfig.contains("auto-containers")){
            var n=section(lootConfig,"auto-containers");
            auto=new AutoContainerLootSettings(bool(n,"enabled"),text(n,"loot-table"),number(n,"chance"),integer(n,"min-rolls"),integer(n,"max-rolls"),integer(n,"max-containers-per-tick"));
        }
        var airdrops=new AirdropSettings(!tables.isEmpty(),defaultTable,8,12,8,24,120);
        if(lootConfig.contains("airdrops")){
            var n=section(lootConfig,"airdrops");
            airdrops=new AirdropSettings(bool(n,"enabled"),text(n,"loot-table"),integer(n,"min-rolls"),integer(n,"max-rolls"),integer(n,"fall-seconds"),integer(n,"max-attempts"),integer(n,"marker-seconds"));
        }
        if(auto.enabled()){require(tables,auto.table(),"auto-containers");new LootTable("auto",auto.minRolls(),auto.maxRolls(),tables.get(auto.table()).entries());}
        if(airdrops.enabled()){require(tables,airdrops.table(),"airdrops");new LootTable("airdrop",airdrops.minRolls(),airdrops.maxRolls(),tables.get(airdrops.table()).entries());}
        var mobs=new MobLootSettings(!tables.isEmpty(),defaultTable,.35,1,1,64,10);
        if(lootConfig.contains("mob-loot")){
            var n=section(lootConfig,"mob-loot");
            mobs=new MobLootSettings(bool(n,"enabled"),text(n,"loot-table"),number(n,"chance"),integer(n,"min-rolls"),integer(n,"max-rolls"),integer(n,"max-drops-per-session"),integer(n,"per-player-cooldown-seconds"));
        }
        if(mobs.enabled()){require(tables,mobs.table(),"mob-loot");mobs.resolvedTable(tables);}
        var horses=HorseSettings.DEFAULT;
        if(lootConfig.contains("horses")){
            var n=section(lootConfig,"horses");
            horses=new HorseSettings(bool(n,"enabled"),integer(n,"max-per-session"),integer(n,"interval-seconds"),integer(n,"max-attempts"),number(n,"min-distance"));
        }
        return new MatchContent(loadouts,tables,maps,mapErrors,auto,airdrops,mobs,horses);
    }
    public static String loadoutYaml(Map<String,LoadoutDefinition> definitions) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var definition : new TreeMap<>(definitions).values()) {
            String path = "loadouts." + definition.id();
            yaml.set(path + ".selected-hotbar-slot", definition.selectedHotbarSlot());
            yaml.createSection(path + ".slots");
            new TreeMap<>(definition.slots()).forEach((slot,item) -> {
                String p = path + ".slots." + slot;
                yaml.set(p + ".format",item.format()); yaml.set(p + ".version",item.version()); yaml.set(p + ".data",item.data());
            });
        }
        return yaml.saveToString();
    }
    private ConfigurationSection read(String file) {
        try {
            Path path = directory.resolve(file).normalize();
            if (!path.startsWith(directory.normalize())) throw new IllegalArgumentException("Unsafe metadata path: " + file);
            for(Path parent=path; parent!=null && parent.startsWith(directory.normalize()); parent=parent.getParent())
                if(Files.isSymbolicLink(parent) || Files.exists(parent) && !parent.toRealPath().startsWith(directory.toRealPath()))
                    throw new IllegalArgumentException("Linked metadata path: " + file);
            YamlConfiguration yaml = new YamlConfiguration(); yaml.load(path.toFile()); return yaml;
        } catch (Exception failure) { throw new IllegalArgumentException(file + ": " + failure.getMessage(), failure); }
    }
    private static ConfigurationSection section(ConfigurationSection node,String key) {
        ConfigurationSection result = node.getConfigurationSection(key);
        if (result == null) throw new IllegalArgumentException("Missing section " + node.getCurrentPath() + "." + key); return result;
    }
    private static String text(ConfigurationSection node,String key) {
        Object value = node.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Expected text: " + node.getCurrentPath() + "." + key); return text;
    }
    private static boolean bool(ConfigurationSection node,String key){if(!node.isBoolean(key))throw new IllegalArgumentException("Expected boolean: "+key);return node.getBoolean(key);}
    private static long whole(ConfigurationSection node,String key) {
        Object value = node.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Expected integer: " + node.getCurrentPath() + "." + key); return ((Number)value).longValue();
    }
    private static int integer(ConfigurationSection node,String key) { return Math.toIntExact(whole(node,key)); }
    private static double number(ConfigurationSection node,String key) {
        Object value = node.get(key); if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Expected finite number: " + key); return number.doubleValue();
    }
    private static List<ConfigurationSection> list(ConfigurationSection node,String key) {
        if (!(node.get(key) instanceof List<?> values)) throw new IllegalArgumentException("Expected list: " + key);
        List<ConfigurationSection> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Expected object in " + key);
            YamlConfiguration entry = new YamlConfiguration(); map.forEach((k,v) -> entry.set(k.toString(),v)); result.add(entry);
        }
        return result;
    }
    private static String unique(ConfigurationSection node,Set<String> ids) {
        String id = text(node,"id"); if (!ids.add(id)) throw new IllegalArgumentException("Duplicate loot id: " + id); return id;
    }
    private static void require(Map<String,?> map,String key,String context) {
        if (!map.containsKey(key)) throw new IllegalArgumentException(context + ": unknown reference " + key);
    }
}
