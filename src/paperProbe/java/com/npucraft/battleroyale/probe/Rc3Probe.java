package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.admin.MapMetadataStore;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.loadout.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.zone.ZoneGeometry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Local integration helper only; never included in the installable BattleRoyale plugin. */
public final class Rc3Probe {
    private final JavaPlugin plugin;
    private boolean busy;
    public Rc3Probe(JavaPlugin plugin) { this.plugin = plugin; }
    public void command(CommandSender sender, String[] args) {
        if (busy) throw new IllegalStateException("RC3 probe already running");
        if (args.length < 2 || args.length > 3 || !Set.of("loadout", "lobby", "rooms").contains(args[1]))
            throw new IllegalArgumentException("p26rc3 loadout [id] | lobby | rooms [map-id]");
        String action = args[1];
        if (action.equals("lobby") && args.length != 2) throw new IllegalArgumentException("p26rc3 lobby");
        String id = args.length == 3 ? args[2] : action.equals("loadout") ? "starter" : "survival";
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Unsafe fixture id");
        busy = true;
        CompletableFuture<String> work;
        try {
            work = switch (action) {
                case "loadout" -> loadout(id);
                case "rooms" -> rooms(id);
                case "lobby" -> lobby();
                default -> throw new IllegalArgumentException(action);
            };
        } catch (Exception error) { work = CompletableFuture.failedFuture(error); }
        work.whenComplete((detail, failure) -> main(() -> {
            busy = false;
            if (failure == null) sender.sendMessage("RC3 " + action.toUpperCase(Locale.ROOT) + " SUCCESS " + detail);
            else {
                Throwable cause = failure;
                while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
                sender.sendMessage("RC3 " + action.toUpperCase(Locale.ROOT) + " FAILED " + cause);
                plugin.getLogger().log(Level.SEVERE, "RC3 " + action + " FAILED", cause);
            }
            return null;
        }));
    }
    private Plugin battleRoyale() {
        return Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale"), "BattleRoyale plugin");
    }
    private Path data() { return battleRoyale().getDataFolder().toPath().toAbsolutePath().normalize(); }
    private Path output(String filename) { return plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("rc3").resolve(filename); }
    private CompletableFuture<String> save(String filename, String text) {
        Path target = output(filename);
        return CompletableFuture.supplyAsync(() -> {
            try { AtomicFiles.write(target, text.getBytes(StandardCharsets.UTF_8)); return target.toString(); }
            catch (Exception error) { throw new CompletionException(error); }
        });
    }
    private CompletableFuture<String> loadout(String id) {
        var serializer = new NativeItemSerializer();
        var source = new TreeMap<Integer,ItemStack>();
        source.put(0, new ItemStack(Material.STONE_SWORD));
        source.put(1, new ItemStack(Material.BOW));
        source.put(2, new ItemStack(Material.BREAD, 8));
        source.put(9, new ItemStack(Material.ARROW, 16));
        source.put(38, new ItemStack(Material.LEATHER_CHESTPLATE));
        var stored = new TreeMap<Integer,StoredItem>();
        source.forEach((slot, item) -> {
            StoredItem bytes = serializer.store(item);
            require(item.equals(serializer.item(bytes)), "Native item roundtrip at slot " + slot);
            stored.put(slot, bytes);
        });
        var definition = new LoadoutDefinition(id, stored, 0);
        String yaml = "config-version: 2\n# Generated on Paper " + plugin.getServer().getMinecraftVersion()
                + " with NativeItemSerializer; inventory slots 0/1/2/9 and chestplate slot 38.\n"
                + MatchContentLoader.loadoutYaml(Map.of(id, definition));
        return save("starter-loadouts.yml", yaml).thenApply(path -> "id=" + id + " items=STONE_SWORD:1,BOW:1,BREAD:8,ARROW:16,LEATHER_CHESTPLATE:1 path=" + path);
    }
    private CompletableFuture<String> rooms(String expectedMap) throws Exception {
        var configuration = new ConfigurationLoader(data()).load();
        var serializer = new NativeItemSerializer();
        var content = new MatchContentLoader(data(), new NativeLootItems(), serializer::item).load(configuration);
        var expected = Map.of("solo", 1, "duo", 2, "squad", 4);
        require(configuration.rooms().stream().map(r -> r.id()).collect(java.util.stream.Collectors.toSet()).equals(expected.keySet()), "Exactly solo, duo and squad rooms");
        require(content.mapErrors().isEmpty(), "No map content errors");
        var report = new YamlConfiguration();
        report.set("platform", plugin.getServer().getVersion());
        report.set("room-count", configuration.rooms().size());
        report.set("lobby-world", configuration.settings().lobbyWorld());
        report.set("game-copy-buffer-blocks", WorldFiles.GAME_COPY_BUFFER_BLOCKS);
        var metadata = new MapMetadataStore(data());
        var map = metadata.overlay(configuration.maps().stream().filter(m -> m.id().equals(expectedMap)).findFirst().orElseThrow());
        int expectedExtent=Boolean.getBoolean("battleroyale.probe.rc11")?5000:10000;
        require(map.playableArea().equals(new PlayableArea(-expectedExtent,expectedExtent,-expectedExtent,expectedExtent)), "Effective playable area must be X/Z +/-"+expectedExtent);
        report.set("map.id", map.id()); report.set("map.path", map.templatePath().toString());
        report.set("map.min-x", map.playableArea().minX()); report.set("map.max-x", map.playableArea().maxX());
        report.set("map.min-z", map.playableArea().minZ()); report.set("map.max-z", map.playableArea().maxZ());
        for (var room : configuration.rooms()) {
            require(room.teamSize() == expected.get(room.id()), "Team size: " + room.id());
            require(room.mapPool().equals(List.of(expectedMap)), "Shared survival map: " + room.id());
            var profile = configuration.zoneProfiles().stream().filter(z -> z.id().equals(room.zoneProfileId())).findFirst().orElseThrow();
            var definition = Objects.requireNonNull(content.loadouts().get(room.loadoutId()), "Room loadout");
            require(!definition.slots().isEmpty(), "Starter loadout must not be empty: " + room.id());
            var inventory = new ArrayList<String>();
            for (var item : new TreeMap<>(definition.slots()).entrySet()) {
                var stack = serializer.item(item.getValue());
                require(stack.equals(serializer.item(serializer.store(stack))), "Configured item roundtrip");
                inventory.add(item.getKey() + ":" + stack.getType() + ":" + stack.getAmount());
            }
            assertStarter(definition, serializer);
            double half = profile.initialHalfSize(room.maxPlayers());
            var random = new Random(73191); var centers = new HashSet<String>();
            for (int sample = 0; sample < 32; sample++) {
                var zone = ZoneGeometry.initial(map.playableArea(), half, random);
                require(map.playableArea().contains(zone), "Random zone stays inside configured map");
                centers.add(zone.centerX() + "," + zone.centerZ());
            }
            require(centers.size() > 1, "Random battle sector is not fixed");
            String prefix = "rooms." + room.id();
            report.set(prefix + ".display-name", room.displayName()); report.set(prefix + ".team-size", room.teamSize());
            report.set(prefix + ".min-players", room.minPlayers()); report.set(prefix + ".max-players", room.maxPlayers());
            report.set(prefix + ".countdown-seconds", room.countdownDuration().getSeconds());
            report.set(prefix + ".pvp-protection-seconds", room.pvpProtectionDuration().getSeconds());
            report.set(prefix + ".map-pool", room.mapPool()); report.set(prefix + ".zone-profile", room.zoneProfileId());
            report.set(prefix + ".max-player-initial-half-size", half); report.set(prefix + ".random-zone-checks", 32);
            report.set(prefix + ".loadout", room.loadoutId()); report.set(prefix + ".decoded-items", inventory);
        }
        return save("rooms-report.yml", report.saveToString()).thenApply(path -> "rooms=3 map=" + expectedMap + " area=+-"+expectedExtent+" nativeLoadouts=OK path=" + path);
    }
    private static void assertStarter(LoadoutDefinition definition, NativeItemSerializer serializer) {
        var amounts = new EnumMap<Material,Integer>(Material.class);
        definition.slots().values().forEach(value -> { var item = serializer.item(value); amounts.merge(item.getType(), item.getAmount(), Integer::sum); });
        require(amounts.getOrDefault(Material.STONE_SWORD, 0) >= 1 && amounts.getOrDefault(Material.BOW, 0) >= 1
                && amounts.getOrDefault(Material.ARROW, 0) >= 16 && amounts.getOrDefault(Material.BREAD, 0) >= 8,
                "Starter weapon/ammunition/food set: " + definition.id());
        var chest = serializer.item(definition.slots().get(38));
        require(chest != null && chest.getType() == Material.LEATHER_CHESTPLATE, "Starter equipped leather chestplate");
    }
    private CompletableFuture<String> lobby() throws Exception {
        var yaml = new YamlConfiguration(); yaml.load(data().resolve("lobby.yml").toFile());
        var settings = LobbySettings.load(yaml);
        require(settings.buildEnabled(), "Lobby structure must be enabled");
        World world = Objects.requireNonNull(plugin.getServer().getWorld(settings.world()), "Configured lobby is loaded");
        var marker = new Properties(); Path markerPath = data().resolve("lobby-structure.properties"); AtomicFiles.safe(markerPath);
        try (var input = Files.newInputStream(markerPath)) { marker.load(input); }
        require("READY".equals(marker.getProperty("state")), "Lobby construction marker READY");
        require(settings.world().equals(marker.getProperty("world")) && world.getUID().toString().equals(marker.getProperty("world-uuid")), "Lobby marker world identity");
        for (var entry : Map.of("x", settings.x(), "y", settings.y(), "z", settings.z(), "radius", settings.radius(), "blueprint", LobbyBlueprint.VERSION).entrySet())
            require(Integer.toString(entry.getValue()).equals(marker.getProperty(entry.getKey())), "Lobby marker " + entry.getKey());
        Path backup = data().resolve("lobby-structure-original.blocks.gz"); AtomicFiles.safe(backup);
        require(Files.isRegularFile(backup), "Lobby original-block backup exists");
        String worldName = world.getName();
        var tickets = new ArrayList<Chunk>(); var futures = new ArrayList<CompletableFuture<?>>();
        for (int x = Math.floorDiv(settings.x()-32,16); x <= Math.floorDiv(settings.x()+32,16); x++)
            for (int z = Math.floorDiv(settings.z()-32,16); z <= Math.floorDiv(settings.z()+32,16); z++)
                futures.add(world.getChunkAtAsync(x,z,false).thenCompose(chunk -> main(() -> {
                    require(chunk != null, "Built lobby chunk exists"); chunk.addPluginChunkTicket(plugin); tickets.add(chunk); return null;
                })));
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenCompose(unused -> scanLobby(world, settings, tickets.size()))
                .whenComplete((report, error) -> main(() -> { tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin)); return null; }))
                .thenCompose(report -> save("lobby-report.yml", report.saveToString()).thenApply(path -> {
                    require(report.getInt("mismatches") == 0, "Lobby block mismatches; report=" + path);
                    require(report.getStringList("errors").isEmpty(), "Lobby labels/spawn errors; report=" + path);
                    return "world=" + worldName + " center=" + settings.x() + "," + settings.y() + "," + settings.z()
                            + " blueprintBlocks=" + report.getInt("blueprint-blocks") + " solid=" + report.getInt("non-air-blocks")
                            + " labels=4 lecterns=4 mismatches=0 path=" + path;
                }));
    }
    private CompletableFuture<YamlConfiguration> scanLobby(World world, LobbySettings settings, int chunks) {
        var result = new CompletableFuture<YamlConfiguration>();
        main(() -> {
            var plan = LobbyBlueprint.blocks(); var materials = new EnumMap<Material,Integer>(Material.class);
            var examples = new ArrayList<String>();
            new BukkitRunnable() {
                int cursor, mismatches;
                public void run() {
                    try {
                        for (int n = 0; n < 1500 && cursor < plan.size(); n++,cursor++) {
                            var block = plan.get(cursor); var p = block.position();
                            Material actual = world.getBlockAt(settings.x()+p.x(),settings.y()+p.y(),settings.z()+p.z()).getType();
                            materials.merge(actual,1,Integer::sum);
                            if (actual != block.material()) {
                                mismatches++;
                                if (examples.size() < 30) examples.add(p + " expected=" + block.material() + " actual=" + actual);
                            }
                        }
                        if (cursor < plan.size()) return;
                        cancel();
                        var report = new YamlConfiguration(); report.set("world", world.getName()); report.set("uuid", world.getUID().toString());
                        report.set("center", List.of(settings.x(),settings.y(),settings.z())); report.set("radius", settings.radius());
                        report.set("chunks", chunks); report.set("blueprint-version", LobbyBlueprint.VERSION); report.set("blueprint-blocks", plan.size());
                        report.set("non-air-blocks", materials.entrySet().stream().filter(e -> !e.getKey().isAir()).mapToInt(Map.Entry::getValue).sum());
                        materials.forEach((type,count) -> report.set("materials."+type.name(),count));
                        report.set("mismatches", mismatches); report.set("mismatch-examples", examples);
                        checkLobbyDetails(world, settings, report); result.complete(report);
                    } catch (Throwable error) { cancel(); result.completeExceptionally(error); }
                }
            }.runTaskTimer(plugin,1,1);
            return null;
        }).whenComplete((unused,error) -> { if (error != null) result.completeExceptionally(error); });
        return result;
    }
    private void checkLobbyDetails(World world, LobbySettings settings, YamlConfiguration report) {
        var errors = new ArrayList<String>(); var spawn = world.getSpawnLocation();
        report.set("spawn", List.of(spawn.getX(),spawn.getY(),spawn.getZ(),(double)spawn.getYaw()));
        if (spawn.getBlockX()!=settings.x() || spawn.getBlockY()!=settings.y()+1 || spawn.getBlockZ()!=settings.z() || Math.abs(Math.IEEEremainder(spawn.getYaw()-180,360))>0.01)
            errors.add("Unexpected world spawn");
        var expected = new LinkedHashMap<String,double[]>();
        expected.put("solo",new double[]{-18,4.1,-12}); expected.put("duo",new double[]{0,4.1,-18});
        expected.put("squad",new double[]{18,4.1,-12}); expected.put("welcome",new double[]{0,4.3,7});
        var headings = Map.of("solo","单人竞技","duo","双人组队","squad","四人小队","welcome","NPUcraft · 大逃杀");
        var key = new NamespacedKey(battleRoyale(),"lobby_structure_label"); var found = new HashMap<String,Integer>();
        for (var display : world.getEntitiesByClass(TextDisplay.class)) {
            String id = display.getPersistentDataContainer().get(key,PersistentDataType.STRING);
            if (id == null) continue;
            found.merge(id,1,Integer::sum); var position = expected.get(id); var location = display.getLocation();
            String text = PlainTextComponentSerializer.plainText().serialize(display.text());
            report.set("labels."+id+".text",text); report.set("labels."+id+".position",List.of(location.getX(),location.getY(),location.getZ()));
            if (position == null || Math.abs(location.getX()-(settings.x()+position[0]+.5))>0.001
                    || Math.abs(location.getY()-(settings.y()+position[1]))>0.001 || Math.abs(location.getZ()-(settings.z()+position[2]+.5))>0.001)
                errors.add("Label coordinates: " + id);
            if (!text.contains(headings.getOrDefault(id,"INVALID_LABEL")) || !text.contains("右键")) errors.add("Label Chinese content: " + id);
            if (!display.isPersistent()) errors.add("Label not persistent: " + id);
        }
        if (!found.keySet().equals(expected.keySet()) || found.values().stream().anyMatch(count -> count != 1)) errors.add("Expected exactly one of each label: " + found);
        report.set("label-counts",found);
        var lecterns = List.of(new int[]{-18,2,-13},new int[]{0,2,-19},new int[]{18,2,-13},new int[]{0,2,7});
        var positions = new ArrayList<String>();
        for (var position : lecterns) {
            int x=settings.x()+position[0],y=settings.y()+position[1],z=settings.z()+position[2]; positions.add(x+","+y+","+z);
            if (world.getBlockAt(x,y,z).getType()!=Material.LECTERN) errors.add("Missing lectern at " + x + "," + y + "," + z);
        }
        report.set("lecterns",positions); report.set("errors",errors);
    }
    private <T> CompletableFuture<T> main(Callable<T> action) {
        var result = new CompletableFuture<T>();
        Runnable run = () -> { try { result.complete(action.call()); } catch (Throwable error) { result.completeExceptionally(error); } };
        if (Bukkit.isPrimaryThread()) run.run(); else plugin.getServer().getScheduler().runTask(plugin,run);
        return result;
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("RC3 assertion: " + description);
    }
}
