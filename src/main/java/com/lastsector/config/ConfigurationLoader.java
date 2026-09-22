package com.lastsector.config;

import com.lastsector.map.*;
import com.lastsector.room.RoomDefinition;
import com.lastsector.zone.ZoneProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Strict YAML adapter. Builds local values first; never mutates live registries. */
public final class ConfigurationLoader {
    private final Path directory;
    public ConfigurationLoader(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    /** Reads all four UTF-8 files or throws ConfigurationException without publishing partial state. */
    public ConfigurationSnapshot load() {
        Node config = read("config.yml");
        String storage = config.section("storage").choice("type", Set.of("sqlite", "mysql"));
        String economy = config.section("economy").choice("provider", Set.of("auto", "coinsengine", "excellenteconomy", "vault", "none"));
        Path runtime = config.section("runtime-worlds").relativePath("directory", directory);
        String lobbyWorld = "world";
        if (config.values().containsKey("lobby")) {
            Node lobby = config.section("lobby");
            lobbyWorld = lobby.text("world");
            lobby.require("use-world-spawn", lobby.bool("use-world-spawn"), "M2 supports only world spawn");
        }
        ZoneUiSettings ui = ZoneUiSettings.DEFAULT;
        if (config.values().containsKey("zone-ui")) {
            Node node = config.section("zone-ui"), bar = node.section("bossbar"), wall = node.section("particle-wall");
            String particle = wall.text("particle");
            try {
                org.bukkit.Particle type = org.bukkit.Particle.valueOf(particle);
                wall.require("particle", type.getDataType() == Void.class, "particle must not require data");
            } catch (IllegalArgumentException error) { throw wall.error("particle", "unknown or unsupported data particle"); }
            try {
                ui = new ZoneUiSettings(bar.bool("enabled"), bar.integer("update-interval-ticks",1), wall.bool("enabled"), particle,
                        wall.integer("interval-ticks",1), wall.number("view-distance",1), wall.number("spacing",.25),
                        wall.number("vertical-spacing",.25), wall.number("height-below-player",0), wall.number("height-above-player",0),
                        wall.integer("max-particles-per-player",1));
            } catch (IllegalArgumentException error) { throw node.error("",error.getMessage()); }
        }
        CombatSettings combat=CombatSettings.DEFAULT;
        Duration window=combat.attributionWindow(),showcase=combat.showcaseDuration();
        double minDamage=combat.assistMinDamage(),share=combat.assistMinShare(),reach=combat.boxReach();
        if(config.values().containsKey("combat")) {
            Node node=config.section("combat"),assist=node.section("assist");
            window=Duration.ofSeconds(node.integer("attribution-seconds",1));
            minDamage=assist.number("min-damage",0); share=assist.number("min-damage-share",0);
        }
        if(config.values().containsKey("match")) showcase=Duration.ofSeconds(config.section("match").integer("winner-showcase-seconds",0));
        if(config.values().containsKey("deathbox")) reach=config.section("deathbox").number("interaction-distance",.1);
        try { combat=new CombatSettings(window,minDamage,share,showcase,reach); }
        catch(IllegalArgumentException error) { throw config.error("combat/match/deathbox",error.getMessage()); }
        DisconnectSettings disconnect=DisconnectSettings.DEFAULT;
        if(config.values().containsKey("disconnect")) {
            Node node=config.section("disconnect");boolean aggro=disconnect.mobAggro();double radius=disconnect.mobRadius();int interval=disconnect.mobInterval();
            if(node.values().containsKey("mob-aggro")){Node mob=node.section("mob-aggro");aggro=mob.bool("enabled");radius=mob.number("radius",0);interval=mob.integer("interval-ticks",1);}
            try {disconnect=new DisconnectSettings(Duration.ofSeconds(node.integer("reconnect-seconds",0)),aggro,radius,interval);}
            catch(IllegalArgumentException error){throw node.error("",error.getMessage());}
        }
        var database=com.lastsector.storage.StorageSettings.defaults(storage,directory);Node storageNode=config.section("storage");
        Path sqlite=database.sqliteFile();String host=database.host(),db=database.database(),username=database.username(),password=database.password();int port=database.port(),timeout=database.timeoutMillis();
        if(storageNode.values().containsKey("sqlite"))sqlite=storageNode.section("sqlite").relativePath("file",directory);
        if(storageNode.values().containsKey("mysql")) {
            Node mysql=storageNode.section("mysql");host=mysql.text("host");db=mysql.text("database");username=mysql.text("username");port=mysql.integer("port",1);timeout=mysql.integer("connection-timeout-ms",100);
            Object secret=mysql.values().get("password");if(!(secret instanceof String))throw new ConfigurationException("config.yml","storage.mysql.password","<redacted>","must be a string");password=(String)secret;
        }
        try{database=new com.lastsector.storage.StorageSettings(storage,sqlite,host,port,db,username,password,timeout);}catch(IllegalArgumentException error){throw new ConfigurationException("config.yml","storage","<redacted>","invalid storage settings");}
        var recovery=com.lastsector.recovery.RecoverySettings.DEFAULT;
        if(config.values().containsKey("recovery")){Node r=config.section("recovery");try{recovery=new com.lastsector.recovery.RecoverySettings(r.bool("enabled"),Duration.ofSeconds(r.integer("checkpoint-seconds",1)),Duration.ofMinutes(r.integer("orphan-delete-after-minutes",1)));}catch(IllegalArgumentException error){throw r.error("",error.getMessage());}}
        PluginSettings settings = new PluginSettings(config.bool("debug"), storage, economy, runtime, lobbyWorld, ui,combat,disconnect,database,recovery);

        Node mapsNode = read("maps.yml").section("maps");
        List<MapTemplate> maps = new ArrayList<>();
        for (String id : mapsNode.keys()) {
            Node map = mapsNode.section(id);
            Node area = map.section("playable-area");
            double minX = area.number("min-x", -Double.MAX_VALUE);
            double maxX = area.number("max-x", -Double.MAX_VALUE);
            double minZ = area.number("min-z", -Double.MAX_VALUE);
            double maxZ = area.number("max-z", -Double.MAX_VALUE);
            area.require("max-x", maxX > minX, "must be > min-x (" + minX + ")");
            area.require("max-z", maxZ > minZ, "must be > min-z (" + minZ + ")");
            maps.add(new MapTemplate(id, map.text("display-name"), map.relativePath("directory", directory),
                    new PlayableArea(minX, maxX, minZ, maxZ)));
        }

        Node profiles = read("zones.yml").section("profiles");
        List<ZoneProfile> zones = new ArrayList<>();
        for (String id : profiles.keys()) {
            Node profile = profiles.section(id);
            List<ZoneProfile.InitialSize> sizes = new ArrayList<>();
            int previousCount = 0;
            for (Node size : profile.nodes("initial-size-by-players")) {
                int count = size.integer("max-players", 1);
                size.require("max-players", count > previousCount, "thresholds must strictly increase");
                sizes.add(new ZoneProfile.InitialSize(count, size.number("half-size", 500)));
                previousCount = count;
            }
            List<ZoneProfile.Stage> stages = new ArrayList<>();
            double previousSize = sizes.stream().mapToDouble(ZoneProfile.InitialSize::halfSize).min().orElseThrow();
            for (Node stage : profile.nodes("stages")) {
                double target = stage.number("target-half-size", 0);
                stage.require("target-half-size", target > 0 && target < previousSize, "must be > 0 and strictly less than every initial half-size / previous target (" + previousSize + ")");
                double base = stage.number("base-damage-per-second", 0);
                double extra = stage.number("extra-damage-per-block", 0);
                double maximum = stage.number("max-damage-per-second", 0);
                stage.require("max-damage-per-second", maximum >= base, "must be >= base-damage-per-second (" + base + ")");
                stages.add(new ZoneProfile.Stage(Duration.ofSeconds(stage.integer("wait-seconds", 0)),
                        Duration.ofSeconds(stage.integer("shrink-seconds", 1)), target, base, extra, maximum));
                previousSize = target;
            }
            zones.add(new ZoneProfile(id, sizes, stages));
        }

        Node roomsNode = read("rooms.yml").section("rooms");
        List<RoomDefinition> rooms = new ArrayList<>();
        for (String id : roomsNode.keys()) {
            Node room = roomsNode.section(id);
            int minimum = room.integer("min-players", 1);
            int maximum = room.integer("max-players", 1);
            room.require("max-players", maximum >= minimum, "must be >= min-players (" + minimum + ")");
            List<String> pool = room.strings("maps");
            for (String mapId : pool)
                room.require("maps", maps.stream().anyMatch(map -> map.id().equals(mapId)), "unknown map id: " + mapId);
            String zoneId = room.text("zone-profile");
            ZoneProfile zone = zones.stream().filter(z -> z.id().equals(zoneId)).findFirst()
                    .orElseThrow(() -> room.error("zone-profile", "unknown zone profile"));
            room.require("max-players", zone.initialSizes().getLast().maxPlayers() >= maximum,
                    "zone profile thresholds must cover room max-players");
            double required = 2 * zone.largestReachableHalfSize(maximum);
            for (MapTemplate map : maps) if (pool.contains(map.id())) {
                PlayableArea area = map.playableArea();
                room.require("maps", area.maxX()-area.minX() >= required && area.maxZ()-area.minZ() >= required,
                        "room=" + id + " map=" + map.id() + " profile=" + zoneId + " requires width/depth >= " + required
                                + ", actual=" + (area.maxX()-area.minX()) + "x" + (area.maxZ()-area.minZ()) + " " + area);
            }
            com.lastsector.room.SpawnSettings spawn = com.lastsector.room.SpawnSettings.DEFAULT;
            if (room.values().containsKey("spawn")) {
                Node node = room.section("spawn");
                spawn = new com.lastsector.room.SpawnSettings(node.number("min-distance",0), node.integer("max-attempts-per-player",1));
            }
            rooms.add(new RoomDefinition(id, room.text("display-name"), minimum, maximum,
                    room.integer("team-size", 1), Duration.ofSeconds(room.integer("pvp-protection-seconds", 0)),
                    pool, room.text("loadout"), zoneId, room.bool("allow-external-spectators"),
                    Duration.ofSeconds(room.values().containsKey("countdown-seconds") ? room.integer("countdown-seconds", 1) : 30), spawn));
        }
        return new ConfigurationSnapshot(settings, rooms, maps, zones);
    }

    private Node read(String file) {
        Path path = directory.resolve(file);
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            yaml.load(reader);
            return new Node(file, "", yaml.getValues(false));
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            throw new ConfigurationException(file, "<document>", path, exception.getMessage());
        }
    }

    /** Small strict reader to avoid Bukkit's silent coercions and numeric defaults. */
    private record Node(String file, String path, Map<?, ?> values) {
        Set<String> keys() {
            Set<String> result = new LinkedHashSet<>();
            for (Object key : values.keySet()) {
                if (!(key instanceof String text) || text.isBlank() || text.contains("."))
                    throw error("", "keys must be nonblank strings without dots");
                result.add(text);
            }
            return result;
        }
        String full(String key) { return path.isEmpty() ? key : key.isEmpty() ? path : path + "." + key; }
        ConfigurationException error(String key, String reason) {
            return new ConfigurationException(file, full(key), key.isEmpty() ? values : values.get(key), reason);
        }
        void require(String key, boolean valid, String reason) { if (!valid) throw error(key, reason); }
        Node section(String key) {
            Object raw = values.get(key);
            if (raw instanceof ConfigurationSection section) return new Node(file, full(key), section.getValues(false));
            if (raw instanceof Map<?, ?> map) return new Node(file, full(key), map);
            throw error(key, "expected a mapping");
        }
        String text(String key) {
            Object value = values.get(key);
            if (!(value instanceof String text) || text.isBlank()) throw error(key, "expected a nonblank string");
            return text;
        }
        String choice(String key, Set<String> choices) {
            String value = text(key);
            require(key, choices.contains(value), "expected one of " + choices);
            return value;
        }
        boolean bool(String key) {
            if (!(values.get(key) instanceof Boolean value)) throw error(key, "expected true or false");
            return value;
        }
        int integer(String key, int minimum) {
            Object value = values.get(key);
            if (!(value instanceof Integer || value instanceof Long)) throw error(key, "expected an integer >= " + minimum);
            long result = ((Number) value).longValue();
            require(key, result >= minimum && result <= Integer.MAX_VALUE, "expected integer in [" + minimum + ", " + Integer.MAX_VALUE + "]");
            return (int) result;
        }
        double number(String key, double minimum) {
            if (!(values.get(key) instanceof Number number)) throw error(key, "expected a finite number >= " + minimum);
            double result = number.doubleValue();
            require(key, Double.isFinite(result) && result >= minimum, "expected a finite number >= " + minimum);
            return result;
        }
        List<String> strings(String key) {
            if (!(values.get(key) instanceof List<?> list) || list.isEmpty()) throw error(key, "expected a nonempty string list");
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String text) || text.isBlank()) throw error(key, "expected only nonblank strings");
                result.add(text);
            }
            return List.copyOf(result);
        }
        List<Node> nodes(String key) {
            if (!(values.get(key) instanceof List<?> list) || list.isEmpty()) throw error(key, "expected a nonempty mapping list");
            List<Node> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> map)) throw error(key, "entry [" + i + "] must be a mapping");
                result.add(new Node(file, full(key) + "[" + i + "]", map));
            }
            return result;
        }
        Path relativePath(String key, Path base) {
            String value = text(key);
            try {
                Path relative = Path.of(value);
                Path resolved = base.resolve(relative).normalize();
                require(key, !relative.isAbsolute() && resolved.startsWith(base) && !resolved.equals(base),
                        "expected a relative child path inside the plugin data directory");
                return resolved;
            } catch (InvalidPathException exception) {
                throw error(key, "invalid filesystem path: " + exception.getReason());
            }
        }
    }
}
