package com.lastsector.config;

import com.lastsector.loadout.*;
import com.lastsector.loot.*;
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
    public MatchContent load(ConfigurationSnapshot configuration) {
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
        root = section(read("loot-tables.yml"), "loot-tables");
        for (String id : root.getKeys(false)) {
            ConfigurationSection node = section(root, id);
            List<LootTable.Entry> entries = new ArrayList<>();
            for (ConfigurationSection entry : list(node,"entries")) {
                String item = text(entry,"item"); items.validate(item);
                entries.add(new LootTable.Entry(item, whole(entry,"weight"), integer(entry,"min-amount"), integer(entry,"max-amount")));
            }
            tables.put(id, new LootTable(id, integer(node,"min-rolls"), integer(node,"max-rolls"), entries));
        }
        Map<String, MapLoot> maps = new LinkedHashMap<>();
        for (var map : configuration.maps()) {
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
        }
        return new MatchContent(loadouts,tables,maps);
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
