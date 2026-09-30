package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.admin.TemplateCommitService;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.NativeItemSerializer;
import com.npucraft.battleroyale.paper.PaperPlayers;
import com.npucraft.battleroyale.paper.PaperWorlds;
import com.npucraft.battleroyale.service.MessageService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicit local-only lifecycle checks. Packaged only in BattleRoyaleTestProbe.jar. */
public final class Paper26Probe {
    private final JavaPlugin plugin;
    private boolean busy;
    public Paper26Probe(JavaPlugin plugin) { this.plugin = plugin; }

    public void command(CommandSender sender, String[] args) {
        if (busy) throw new IllegalStateException("P26 probe already running");
        if (args.length == 0 || !Set.of("p26generate", "p26verify", "p26export").contains(args[0]))
            throw new IllegalArgumentException("p26generate <city|desert> | p26verify | p26export <city|desert>");
        if (!args[0].equals("p26verify") && args.length != 2)
            throw new IllegalArgumentException("Generation/export requires city or desert");
        String id = args[0].equals("p26verify") ? "city" : args[1];
        if (!Set.of("city", "desert").contains(id)) throw new IllegalArgumentException("Unknown fixture map");
        String label = switch (args[0]) { case "p26generate" -> "GENERATED " + id; case "p26export" -> "EXPORT " + id; default -> "VERIFY"; };
        busy = true;
        CompletableFuture<String> task;
        try { task = switch (args[0]) { case "p26generate" -> generate(id); case "p26export" -> export(id); default -> verify(); }; }
        catch (Exception error) { task = CompletableFuture.failedFuture(error); }
        task.whenComplete((detail, failure) -> main(() -> {
            busy = false;
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null && (cause instanceof CompletionException || cause instanceof ExecutionException)) cause = cause.getCause();
                sender.sendMessage("P26 " + label + " FAILED " + cause);
                plugin.getLogger().log(Level.SEVERE, "P26 " + label + " FAILED", cause);
            } else sender.sendMessage("P26 " + label + " SUCCESS " + detail);
            return null;
        }));
    }
    private Path data() {
        return Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale"))
                .getDataFolder().toPath().toAbsolutePath().normalize();
    }
    private CompletableFuture<String> generate(String id) {
        Path target = data().resolve("maps").resolve(id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Existing template refused: " + target);
        String keyPart = id + "_" + UUID.randomUUID().toString().replace("-", "");
        NamespacedKey key = new NamespacedKey("battleroyale_fixture", keyPart);
        Path expected = plugin.getServer().getLevelDirectory().toAbsolutePath().normalize().resolve("dimensions/battleroyale_fixture").resolve(keyPart);
        if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Fixture collision");
        long seed = new java.security.SecureRandom().nextLong();
        World world = Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).seed(seed)));
        require(world.getKey().equals(key) && world.getWorldPath().toAbsolutePath().normalize().equals(expected), "Generated world identity");
        var chunks = new ArrayList<CompletableFuture<?>>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            int cx = x, cz = z;
            chunks.add(world.getChunkAtAsync(x, z, true).thenAccept(chunk -> world.addPluginChunkTicket(cx, cz, plugin)));
        }
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenCompose(ignored -> main(() -> {
            String originalBlock = world.getBlockAt(8, 160, 8).getBlockData().getAsString();
            world.getBlockAt(8, 160, 8).setType(marker(id), false);
            world.save(); world.removePluginChunkTickets(plugin);
            require(plugin.getServer().unloadWorld(world, true), "Fixture unload");
            return originalBlock;
        })).thenCompose(originalBlock -> io(() -> {
            AtomicFiles.safe(expected); AtomicFiles.safe(target);
            require(LevelData.validateDimension(expected) == seed, "Generated seed persisted");
            Files.createDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Existing template refused: " + target);
            var fixture = new Properties(); fixture.setProperty("map", id); fixture.setProperty("original-block", originalBlock);
            fixture.setProperty("marker", marker(id).name()); fixture.setProperty("position", "8,160,8");
            try (var out = Files.newOutputStream(expected.resolve(".p26-fixture.properties"), StandardOpenOption.CREATE_NEW)) { fixture.store(out, "Local P26 fixture restoration data; removed on export"); }
            Files.move(expected, target, StandardCopyOption.ATOMIC_MOVE);
            return "seed=" + seed + " path=" + target + " chunks=9 marker=8,160,8:" + marker(id);
        }));
    }
    private static Material marker(String id) { return id.equals("city") ? Material.EMERALD_BLOCK : Material.GOLD_BLOCK; }

    /** Restore the exact pre-test block before exporting a normal terrain template. */
    private CompletableFuture<String> export(String id) throws IOException {
        var server = plugin.getServer(); Path root = data();
        var map = new MapTemplate(id, id, root.resolve("maps").resolve(id), new PlayableArea(-1024,1024,-1024,1024));
        var files = WorldFiles.paper262(root, new RuntimeLayout(server.getLevelDirectory(), RuntimeLayout.MAINTENANCE),
                List.of(map.templatePath()), server.getWorlds().stream().map(World::getWorldPath).toList());
        var lobby = Objects.requireNonNull(server.getWorld(NamespacedKey.minecraft("overworld")));
        var gateway = new PaperWorlds(server, new PaperPlayers(server, lobby.getName(), new MessageService(plugin.getLogger())));
        return io(() -> {
            Path fixtureFile = map.templatePath().resolve(".p26-fixture.properties"); AtomicFiles.safe(fixtureFile);
            var props = new Properties(); try (var in = Files.newInputStream(fixtureFile)) { props.load(in); }
            require(id.equals(props.getProperty("map")) && "8,160,8".equals(props.getProperty("position"))
                    && marker(id).name().equals(props.getProperty("marker")), "Export fixture identity");
            return new Export(files.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID()), props.getProperty("original-block"));
        }).thenCompose(state -> main(() -> {
            gateway.load(state.world()); World world = Objects.requireNonNull(server.getWorld(state.world().worldName()));
            require(world.getBlockAt(8,160,8).getType() == marker(id), "Export will restore only our known test marker");
            world.getBlockAt(8,160,8).setBlockData(Bukkit.createBlockData(Objects.requireNonNull(state.original())), false);
            world.save(); require(server.unloadWorld(world, true), "Export clone save/unload");
            return state;
        })).thenCompose(state -> io(() -> {
            Files.delete(state.world().runtimePath().resolve(".p26-fixture.properties"));
            Path backup = new TemplateCommitService(root, files).commit(map, state.world(), files, true);
            require(!Files.exists(map.templatePath().resolve(".p26-fixture.properties")), "Export excludes fixture metadata");
            return "seed=" + LevelData.validateDimension(map.templatePath()) + " originalBlock=" + state.original()
                    + " path=" + map.templatePath() + " backup=" + backup;
        }));
    }
    private record Export(GameWorld world, String original) {}

    private static final class Verification {
        final Path data;
        final MapTemplate map;
        final WorldFiles files;
        final PaperWorlds gateway;
        final List<Path> protectedWorlds;
        final Path level;
        GameWorld first, second;
        UUID firstId, secondId;
        String templateHash;
        Verification(Path data, Path level, List<Path> protectedWorlds, PaperWorlds gateway) throws IOException {
            this.data = data; this.level = level; this.protectedWorlds = protectedWorlds; this.gateway = gateway;
            map = new MapTemplate("city", "City", data.resolve("maps/city"), new PlayableArea(-1024,1024,-1024,1024));
            files = WorldFiles.paper262(data, new RuntimeLayout(level, RuntimeLayout.GAME), List.of(map.templatePath()), protectedWorlds);
        }
    }
    private CompletableFuture<String> verify() throws IOException {
        var server = plugin.getServer();
        World lobby = Objects.requireNonNull(server.getWorld(NamespacedKey.minecraft("overworld")));
        var protectedWorlds = server.getWorlds().stream().map(World::getWorldPath)
                .filter(path -> !path.toAbsolutePath().normalize().startsWith(new RuntimeLayout(server.getLevelDirectory(), RuntimeLayout.GAME).runtimeRoot())).toList();
        var gateway = new PaperWorlds(server, new PaperPlayers(server, lobby.getName(), new MessageService(plugin.getLogger())));
        var v = new Verification(data(), server.getLevelDirectory().toAbsolutePath().normalize(), protectedWorlds, gateway);
        return io(() -> {
            v.templateHash = hashTree(v.files.validateTemplate(v.map));
            v.first = v.files.copy(UUID.randomUUID(), "p26_first", v.map);
            v.second = v.files.copy(UUID.randomUUID(), "p26_second", v.map);
            return v;
        }).thenCompose(ignored -> main(() -> {
            gateway.load(v.first); gateway.load(v.second);
            World first = Objects.requireNonNull(server.getWorld(v.first.worldName()));
            World second = Objects.requireNonNull(server.getWorld(v.second.worldName()));
            v.firstId = first.getUID(); v.secondId = second.getUID();
            require(!v.firstId.equals(v.secondId), "Clones must have different world UUIDs");
            require(first.getSeed() == v.first.expectedSeed() && second.getSeed() == v.first.expectedSeed(), "Clone seeds");
            require(first.getBlockAt(8,160,8).getType() == marker("city") && second.getBlockAt(8,160,8).getType() == marker("city"), "Template region blocks copied");
            require(server.getWorlds().stream().noneMatch(w -> w.getWorldPath().toAbsolutePath().normalize().startsWith(v.map.templatePath())), "Template never loaded");
            first.getBlockAt(9,160,8).setType(Material.DIAMOND_BLOCK, false);
            first.save(); require(server.unloadWorld(first, true), "First clone save/unload");
            return null;
        })).thenCompose(ignored -> io(() -> v.files.recovery(v.first.sessionId(), v.first.roomId(), v.map,
                v.first.worldName(), v.first.runtimePath().getFileName().toString())))
          .thenCompose(recovered -> main(() -> {
            gateway.load(recovered);
            World world = Objects.requireNonNull(server.getWorld(recovered.worldName()));
            require(world.getUID().equals(v.firstId), "Recovery must preserve persisted UUID");
            require(world.getBlockAt(9,160,8).getType() == Material.DIAMOND_BLOCK, "Recovery block state");
            require(Objects.requireNonNull(server.getWorld(v.second.worldName())).getBlockAt(9,160,8).getType() != Material.DIAMOND_BLOCK, "Clone block isolation");
            gateway.unload(recovered); gateway.unload(v.second);
            return null;
        })).thenCompose(ignored -> io(() -> {
            v.files.delete(v.first, true); v.files.delete(v.first, true); v.files.delete(v.second, true);
            require(!Files.exists(v.first.runtimePath()) && !Files.exists(v.second.runtimePath()), "Clone cleanup");
            require(v.templateHash.equals(hashTree(v.files.validateTemplate(v.map))), "Template bytes unchanged after clone lifecycle");
            Path target = v.data.resolve("maps/p26_verify_" + UUID.randomUUID().toString().replace("-", ""));
            copyTree(v.files.validateTemplate(v.map), target);
            var map = new MapTemplate("p26_verify", "Probe maintenance", target, v.map.playableArea());
            var files = WorldFiles.paper262(v.data, new RuntimeLayout(v.level, RuntimeLayout.MAINTENANCE), List.of(target), v.protectedWorlds);
            return new Maintenance(map, files, files.copy(UUID.randomUUID(), "maintenance", map, "MAINTENANCE", UUID.randomUUID()));
        })).thenCompose(m -> main(() -> {
            gateway.load(m.world());
            World world = Objects.requireNonNull(server.getWorld(m.world().worldName()));
            world.getBlockAt(10,160,8).setType(Material.REDSTONE_BLOCK, false); world.save();
            require(server.unloadWorld(world, true), "Maintenance save/unload");
            return m;
        })).thenCompose(m -> io(() -> {
            var commits = new TemplateCommitService(v.data, m.files());
            Path backup = commits.commit(m.map(), m.world(), m.files(), true);
            require(Files.isDirectory(backup) && !Files.exists(m.map().templatePath().resolve(WorldFiles.MARKER)), "Committed template plus original backup");
            require(LevelData.validateDimension(m.map().templatePath()) == v.first.expectedSeed(), "Commit preserves seed");
            GameWorld next = m.files().copy(UUID.randomUUID(), "maintenance", m.map(), "MAINTENANCE", UUID.randomUUID());
            Path target = m.map().templatePath(); String committed = hashTree(target);
            Files.writeString(next.runtimePath().resolve("p26-rollback.txt"), "replacement");
            Path old = target.resolveSibling(target.getFileName() + ".backup-" + next.sessionId());
            var props = new Properties(); props.setProperty("storageLayout", RuntimeLayout.STORAGE_ID);
            props.setProperty("target", v.data.relativize(target).toString()); props.setProperty("backup", v.data.relativize(old).toString());
            props.setProperty("source", next.runtimePath().getFileName().toString()); props.setProperty("token", next.sessionId().toString()); props.setProperty("state", "PREPARED");
            Path journal = v.data.resolve("maintenance-journals/p26_verify.properties");
            Files.createDirectories(journal.getParent());
            try (var out = Files.newOutputStream(journal, StandardOpenOption.CREATE_NEW)) { props.store(out, "P26 deliberate interrupted commit fixture"); }
            Files.move(target, old, StandardCopyOption.ATOMIC_MOVE); Files.move(next.runtimePath(), target, StandardCopyOption.ATOMIC_MOVE);
            commits.recover(m.map());
            require(committed.equals(hashTree(target)), "Interrupted commit restores original bytes");
            require(Files.exists(next.runtimePath().resolve("p26-rollback.txt")), "Interrupted replacement returned to owned clone");
            m.files().delete(next, true);
            require(v.templateHash.equals(hashTree(v.files.validateTemplate(v.map))), "Original city template unchanged");
            return null;
        })).thenCompose(ignored -> main(() -> {
            NativeItemSerializer serializer = new NativeItemSerializer();
            Material spear = Objects.requireNonNull(Material.matchMaterial("COPPER_SPEAR"), "26.2 copper spear material");
            ItemStack item = new ItemStack(spear); var meta = item.getItemMeta();
            meta.displayName(Component.text("P26 native spear")); meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "p26_item"), PersistentDataType.STRING, "component-and-pdc");
            item.setItemMeta(meta); require(item.equals(serializer.item(serializer.store(item))), "Native spear item components/PDC roundtrip");
            return "uuid1=" + v.firstId + " uuid2=" + v.secondId + " seed=" + v.first.expectedSeed() + " clone/recovery/cleanup/commit/rollback/native-items";
        })).thenCompose(detail -> io(() -> {
            Path legacy = plugin.getServer().getWorldContainer().toPath().resolve("p26-legacy-item.base64");
            return Map.entry(detail, Files.isRegularFile(legacy) ? Files.readString(legacy).trim() : "");
        })).thenCompose(pair -> main(() -> {
            if (!pair.getValue().isEmpty()) {
                var serializer = new NativeItemSerializer(); ItemStack legacy = serializer.deserialize(Base64.getMimeDecoder().decode(pair.getValue()), 1);
                ItemStack original = legacy.clone();
                for (int round = 0; round < 3; round++) legacy = serializer.item(serializer.store(legacy));
                require(original.equals(legacy), "Legacy native item upgrade repeated roundtrip");
                return pair.getKey() + " legacy-item=" + legacy.getType() + " display=" + legacy.getItemMeta().displayName()
                        + " lore=" + legacy.getItemMeta().lore();
            }
            return pair.getKey() + " legacy-item=NOT_PROVIDED";
        }));
    }
    private record Maintenance(MapTemplate map, WorldFiles files, GameWorld world) {}
    private <T> CompletableFuture<T> main(Callable<T> action) {
        var result = new CompletableFuture<T>();
        plugin.getServer().getScheduler().runTask(plugin, () -> { try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        return result;
    }
    private static <T> CompletableFuture<T> io(Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> { try { return action.call(); } catch (Exception failure) { throw new CompletionException(failure); } });
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("P26 assertion: " + description);
    }
    private static String hashTree(Path root) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                AtomicFiles.safe(file); hash.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8)); hash.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void copyTree(Path source, Path target) throws IOException {
        AtomicFiles.safe(source); AtomicFiles.safe(target); Files.createDirectory(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                AtomicFiles.safe(dir); Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty()) Files.createDirectory(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                AtomicFiles.safe(file); if (!attrs.isRegularFile()) throw new IOException("Non-regular fixture entry");
                Files.copy(file, target.resolve(source.relativize(file)), LinkOption.NOFOLLOW_LINKS); return FileVisitResult.CONTINUE;
            }
        });
    }
}
