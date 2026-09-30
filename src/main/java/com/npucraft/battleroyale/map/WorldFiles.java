package com.npucraft.battleroyale.map;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import com.npucraft.battleroyale.zone.Zone;

/** File-only world store. Every recursive operation is bounded, ownership checked and NOFOLLOW. */
public final class WorldFiles {
    public static final String MARKER = ".battleroyale-runtime";
    /** One region of terrain beyond the initial zone covers nearby chunk loading and boundary movement. */
    public static final int GAME_COPY_BUFFER_BLOCKS = 512;
    private static final Pattern TERRAIN_FILE = Pattern.compile("([rc])\\.(-?\\d+)\\.(-?\\d+)\\.(mca|mcc)");
    private final Path dataRoot, runtimeRoot, container;
    private final List<Path> templates, protectedWorlds;
    private final FileCopier copier;
    private final RuntimeLayout layout;
    /** Injectable IO fault boundary used by filesystem tests. */
    @FunctionalInterface public interface FileCopier { void copy(Path source, Path target) throws IOException; }
    public WorldFiles(Path dataRoot, Path runtimeRoot, Path container, List<Path> templates, List<Path> protectedWorlds) throws IOException {
        this(dataRoot, runtimeRoot, container, templates, protectedWorlds,
                (source, target) -> Files.copy(source, target, LinkOption.NOFOLLOW_LINKS));
    }
    public WorldFiles(Path dataRoot, Path runtimeRoot, Path container, List<Path> templates, List<Path> protectedWorlds, FileCopier copier) throws IOException {
        this(dataRoot, runtimeRoot, container, templates, protectedWorlds, copier, null);
    }
    public static WorldFiles paper262(Path dataRoot, RuntimeLayout layout, List<Path> templates, List<Path> protectedWorlds) throws IOException {
        return new WorldFiles(dataRoot, layout.runtimeRoot(), layout.levelDirectory(), templates, protectedWorlds,
                (source, target) -> Files.copy(source, target, LinkOption.NOFOLLOW_LINKS), layout);
    }
    private WorldFiles(Path dataRoot, Path runtimeRoot, Path container, List<Path> templates, List<Path> protectedWorlds, FileCopier copier, RuntimeLayout layout) throws IOException {
        this.copier = copier;
        this.layout = layout;
        this.dataRoot = normalized(dataRoot); this.runtimeRoot = normalized(runtimeRoot); this.container = normalized(container);
        this.templates = templates.stream().map(WorldFiles::normalized).toList();
        this.protectedWorlds = protectedWorlds.stream().map(WorldFiles::normalized).toList();
        noLinks(this.dataRoot); noLinks(this.container); noLinks(this.runtimeRoot);
        if (layout == null) {
            if (!child(this.runtimeRoot, this.dataRoot) || !child(this.runtimeRoot, this.container))
                throw new IOException("Runtime root must be a strict child of plugin data and the Paper world container: " + runtimeRoot);
        } else if (!this.runtimeRoot.equals(layout.runtimeRoot()) || !this.container.equals(layout.levelDirectory())
                || overlap(this.dataRoot, this.runtimeRoot)) {
            throw new IOException("Invalid dedicated Paper dimension namespace");
        }
        for (Path path : this.templates) if (overlap(path, this.runtimeRoot)) throw new IOException("Runtime root overlaps template: " + path);
        for (Path path : this.protectedWorlds) if (overlap(path, this.runtimeRoot)) throw new IOException("Runtime root overlaps server world: " + path);
    }
    /** Full UUID is retained; only a bounded sanitized room fragment contributes to the leaf name. */
    public static String leafName(String room, UUID session) {
        String safe = room.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (safe.length() > 24) safe = safe.substring(0, 24);
        if (safe.isEmpty()) safe = "room";
        return "br_" + safe + "_" + session.toString().replace("-", "");
    }
    public GameWorld descriptor(UUID session, String room, MapTemplate template) {
        Path target = runtimeRoot.resolve(leafName(room, session));
        String name = worldName(target);
        return new GameWorld(session, room, name, target, template);
    }
    /** Validates complete source first. Failed partial copies keep a marker unless cleanup succeeds. */
    public GameWorld copy(UUID session, String room, MapTemplate template) throws IOException { return copy(session,room,template,"GAME",session); }
    /** Copies complete intersecting region files; maintenance/editor callers keep the full-copy overload. */
    public GameWorld copy(UUID session, String room, MapTemplate template, Zone initialZone) throws IOException {
        return copy(session, room, template, "GAME", session, RegionSelection.around(Objects.requireNonNull(initialZone)));
    }
    public GameWorld copy(UUID session,String room,MapTemplate template,String type,UUID owner)throws IOException {
        return copy(session, room, template, type, owner, null);
    }
    private GameWorld copy(UUID session, String room, MapTemplate template, String type, UUID owner,
            RegionSelection selection) throws IOException {
        if (selection != null && !type.equals("GAME")) throw new IOException("Only GAME worlds may use a selected copy area");
        if(!Set.of("GAME","EDITOR","MAINTENANCE").contains(type) || layout != null && !layout.permits(type))
            throw new IOException("Runtime type does not belong to this namespace");
        long cloneStarted=System.nanoTime();
        Path source = validateTemplate(template);
        GameWorld descriptor = descriptor(session, room, template);
        GameWorld world = new GameWorld(session, room, descriptor.worldName(), descriptor.runtimePath(), template,
                worldSeed(source));
        validateTarget(world);
        Files.createDirectories(runtimeRoot); noLinks(runtimeRoot);
        Files.createDirectory(world.runtimePath()); // Never merge with or overwrite an existing directory.
        try {
            writeMarker(world,type,owner);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    noLinks(directory);
                    Path relative = source.relativize(directory);
                    if (!relative.toString().isEmpty() && excluded(relative)) return FileVisitResult.SKIP_SUBTREE;
                    if (!relative.toString().isEmpty()) Files.createDirectory(world.runtimePath().resolve(relative));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    noLinks(file);
                    if (!attributes.isRegularFile()) throw new IOException("Unsupported template entry: " + file);
                    Path relative = source.relativize(file);
                    if (!excluded(relative) && (selection == null || selection.includes(relative))) {
                        Path destination = world.runtimePath().resolve(relative);
                        noLinks(destination.getParent());
                        copier.copy(file, destination);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            worldSeed(world.runtimePath());
            checkTree(world.runtimePath());
            com.npucraft.battleroyale.admin.PerformanceMetricsService.LIVE.record(com.npucraft.battleroyale.admin.PerformanceMetricsService.Timer.WORLD_CLONE,System.nanoTime()-cloneStarted);
            return world;
        } catch (IOException | RuntimeException failure) {
            try { delete(world, true); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /** Inclusive zone boundaries are rounded outward to whole 512 x 512 block region files. */
    private record RegionSelection(long minX, long maxX, long minZ, long maxZ) {
        static RegionSelection around(Zone zone) {
            return new RegionSelection(region(zone.minX() - GAME_COPY_BUFFER_BLOCKS),
                    region(zone.maxX() + GAME_COPY_BUFFER_BLOCKS),
                    region(zone.minZ() - GAME_COPY_BUFFER_BLOCKS),
                    region(zone.maxZ() + GAME_COPY_BUFFER_BLOCKS));
        }
        private static long region(double block) { return (long) Math.floor(block / 512.0); }
        boolean includes(Path relative) throws IOException {
            // Only direct Anvil files are cropped. Saved generator/settings and other data stay intact.
            if (relative.getNameCount() != 2
                    || !Set.of("region", "entities", "poi").contains(relative.getName(0).toString())) return true;
            var name = TERRAIN_FILE.matcher(relative.getFileName().toString());
            if (!name.matches() || name.group(1).equals("r") != name.group(4).equals("mca")) return true;
            long x, z;
            try { x = Long.parseLong(name.group(2)); z = Long.parseLong(name.group(3)); }
            catch (NumberFormatException malformed) { throw new IOException("Invalid Anvil coordinates: " + relative, malformed); }
            // An .mca contains references to all its external chunks, including those outside the exact
            // buffered square. Keep every .mcc in each selected region so none of those references break.
            if (name.group(1).equals("c")) { x = Math.floorDiv(x, 32L); z = Math.floorDiv(z, 32L); }
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
    public Path runtimeRoot(){return runtimeRoot;}
    public record OwnedRuntime(GameWorld world,String status,Instant orphanedAt) {}
    /** Only direct children with a fully validated marker are returned. Unmarked data is never adopted. */
    public List<OwnedRuntime> ownedChildren(java.util.function.Consumer<String> warning)throws IOException {
        if(Files.notExists(runtimeRoot,LinkOption.NOFOLLOW_LINKS))return List.of();noLinks(runtimeRoot);var result=new ArrayList<OwnedRuntime>();
        try(var children=Files.list(runtimeRoot)){for(Path path:children.toList())try {
            noLinks(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;Path marker=path.resolve(MARKER);noLinks(marker);
            if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) || Files.size(marker)>16384){warning.accept("Unmarked/invalid runtime directory retained: "+path.getFileName());continue;}
            var properties=new Properties();try(var input=Files.newInputStream(marker)){properties.load(input);}
            UUID id=UUID.fromString(properties.getProperty("sessionId"));String room=properties.getProperty("roomId"),map=properties.getProperty("mapId");
            var template=new MapTemplate(map,map,dataRoot.resolve("recovery-template-identity"),new PlayableArea(-1,1,-1,1));
            var world=new GameWorld(id,room,properties.getProperty("worldName"),path,template);validateTarget(world);validateMarker(world);
            String status=properties.getProperty("status","ACTIVE");if(!Set.of("ACTIVE","ORPHANED").contains(status))throw new IOException("Invalid runtime marker status");
            Instant orphan=status.equals("ORPHANED")?Instant.parse(properties.getProperty("orphanedAt")):null;result.add(new OwnedRuntime(world,status,orphan));
        }catch(Exception error){warning.accept("Unsafe runtime candidate retained: "+path.getFileName()+" ("+error.getClass().getSimpleName()+")");}}return List.copyOf(result);
    }
    public GameWorld recovery(UUID id,String room,MapTemplate template,String name,String relative)throws IOException {
        var expected=descriptor(id,room,template);
        if(!expected.worldName().equals(name) || !expected.runtimePath().getFileName().toString().equals(relative))throw new IOException("Recovery path identity mismatch");
        validateLoad(expected);checkTree(expected.runtimePath());
        var properties=marker(expected);if(!properties.getProperty("type","GAME").equals("GAME"))throw new IOException("Not a GAME runtime");if(!properties.getProperty("status","ACTIVE").equals("ACTIVE"))throw new IOException("World is not ACTIVE");
        return new GameWorld(id,room,name,expected.runtimePath(),template,worldSeed(expected.runtimePath()));
    }
    private Properties marker(GameWorld world)throws IOException {validateTarget(world);validateMarker(world);var p=new Properties();try(var input=Files.newInputStream(world.runtimePath().resolve(MARKER))){p.load(input);}return p;}
    public void orphan(GameWorld world,Instant now,String reason)throws IOException {
        var p=marker(world);if(p.getProperty("status","ACTIVE").equals("ORPHANED"))return;
        p.setProperty("status","ORPHANED");p.setProperty("orphanedAt",now.toString());p.setProperty("reason",reason);
        Path temporary=world.runtimePath().resolve(MARKER+".tmp-"+UUID.randomUUID());noLinks(temporary);
        try(var out=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {var bytes=new java.io.ByteArrayOutputStream();p.store(bytes,"BattleRoyale owned runtime world");out.write(java.nio.ByteBuffer.wrap(bytes.toByteArray()));out.force(true);}
        try{Files.move(temporary,world.runtimePath().resolve(MARKER),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    public boolean deleteOrphan(OwnedRuntime candidate,Instant now,java.time.Duration minimumAge,Set<Path> loaded,Set<UUID> referenced)throws IOException {
        var world=candidate.world();if(loaded.contains(world.runtimePath()) || referenced.contains(world.sessionId()))return false;
        var p=marker(world);if(!"ORPHANED".equals(p.getProperty("status")))return false;
        Instant since=Instant.parse(p.getProperty("orphanedAt"));if(!since.equals(candidate.orphanedAt()) || now.isBefore(since.plus(minimumAge)))return false;
        delete(world,true);return true;
    }
    private boolean excluded(Path relative) {
        String name = relative.getFileName().toString();
        return Set.of("session.lock", "uid.dat", "players", "playerdata", "stats", "advancements", MARKER).contains(name)
                || layout != null && Set.of("data/paper/metadata.dat", "data/paper/metadata.dat_old")
                    .contains(relative.toString().replace('\\', '/'));
    }
    public Path validateTemplate(MapTemplate template) throws IOException {
        Path source = normalized(template.templatePath());
        if (!templates.contains(source) || !child(source, dataRoot) || overlap(source, runtimeRoot))
            throw new IOException("Template path outside configured template boundary: " + source);
        for (Path protectedPath : protectedWorlds)
            if (overlap(source, protectedPath)) throw new IOException("Template overlaps a loaded server world: " + source);
        noLinks(source);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Template directory does not exist: " + source);
        checkTree(source); // Refuse all links, even in excluded data directories.
        if (Files.exists(source.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Template is a marked runtime world: " + source);
        Path content = layout == null ? source : LevelData.dimension(source);
        if (Files.exists(content.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Template dimension is a marked runtime world");
        worldSeed(content);
        return content;
    }
    /** Call only with a server-thread unload acknowledgement, or for a clone never offered to Bukkit. */
    public void delete(GameWorld world, boolean confirmedUnloaded) throws IOException {
        long cleanupStarted=System.nanoTime();
        if (!confirmedUnloaded) throw new IOException("Refusing deletion without unload confirmation: " + world.runtimePath());
        validateTarget(world);
        Path target = world.runtimePath();
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return;
        validateMarker(world);
        byte[] retainedMarker=Files.readAllBytes(target.resolve(MARKER));
        checkTree(target); // All-or-refuse link preflight before deleting any content.
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                validateTarget(world); validateMarker(world);
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                        noLinks(directory); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        noLinks(file);
                        if (!file.equals(target.resolve(MARKER))) Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        noLinks(directory);
                        if (!directory.equals(target)) Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
                // Marker is last; restore it if the final directory delete fails.
                Files.delete(target.resolve(MARKER));
                try { Files.delete(target); }
                catch (IOException error) { Files.write(target.resolve(MARKER),retainedMarker,StandardOpenOption.CREATE_NEW); throw error; }
                com.npucraft.battleroyale.admin.PerformanceMetricsService.LIVE.record(com.npucraft.battleroyale.admin.PerformanceMetricsService.Timer.WORLD_CLEANUP,System.nanoTime()-cleanupStarted);
                return;
            } catch (IOException error) {
                failure = error;
                if (attempt < 2) {
                    try { Thread.sleep(100L * (attempt + 1)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Cleanup interrupted", interrupted); }
                }
            }
        }
        throw new IOException("Cleanup failed; owned directory retained: " + target, failure);
    }
    private void validateTarget(GameWorld world) throws IOException {
        Path target = normalized(world.runtimePath());
        if (!target.equals(world.runtimePath()) || !runtimeRoot.equals(target.getParent())
                || !target.getFileName().toString().equals(leafName(world.roomId(), world.sessionId()))
                || !world.worldName().equals(worldName(target)))
            throw new IOException("Runtime identity/path mismatch: " + target);
        if (!child(target, runtimeRoot) || target.equals(container) || target.equals(dataRoot))
            throw new IOException("Unsafe delete/copy boundary: " + target);
        for (Path path : templates) if (overlap(target, path)) throw new IOException("Runtime overlaps template: " + path);
        for (Path path : protectedWorlds) if (overlap(target, path)) throw new IOException("Runtime overlaps protected world: " + path);
        noLinks(target);
    }
    /** Fast final identity/marker check immediately before server-thread world load. */
    public void validateLoad(GameWorld world) throws IOException {
        validateTarget(world); validateMarker(world);
        Path required = world.runtimePath().resolve(layout == null ? "level.dat" : LevelData.WORLD_GEN_SETTINGS);
        noLinks(required);
        if (!Files.isRegularFile(required, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Clone world generation data is no longer present");
    }
    public void validateClone(GameWorld world)throws IOException {validateLoad(world);checkTree(world.runtimePath());worldSeed(world.runtimePath());}
    private void validateMarker(GameWorld world) throws IOException {
        Path marker = world.runtimePath().resolve(MARKER); noLinks(marker);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 16384)
            throw new IOException("Missing/invalid BattleRoyale ownership marker: " + marker);
        Properties properties = new Properties();
        try (var input = Files.newInputStream(marker)) { properties.load(input); }
        if (!world.sessionId().toString().equals(properties.getProperty("sessionId"))
                || !world.roomId().equals(properties.getProperty("roomId"))
                || !world.template().id().equals(properties.getProperty("mapId"))
                || !world.worldName().equals(properties.getProperty("worldName")))
            throw new IOException("Ownership marker does not match this session: " + marker);
        if(!Set.of("GAME","EDITOR","MAINTENANCE").contains(properties.getProperty("type","GAME")))throw new IOException("Invalid ownership type");
        if (layout != null && (!RuntimeLayout.STORAGE_ID.equals(properties.getProperty("storageLayout"))
                || !layout.namespace().equals(properties.getProperty("namespace"))
                || !layout.permits(properties.getProperty("type", ""))))
            throw new IOException("Legacy or foreign namespace runtime is not adopted");
        if(!properties.getProperty("type","GAME").equals("GAME"))try{UUID.fromString(properties.getProperty("editorUUID"));}catch(RuntimeException error){throw new IOException("Invalid maintenance owner",error);}
        try { Instant.parse(properties.getProperty("createdAt")); }
        catch (RuntimeException error) { throw new IOException("Invalid ownership timestamp", error); }
    }
    private void writeMarker(GameWorld world,String type,UUID owner) throws IOException {
        noLinks(world.runtimePath());
        Properties properties = new Properties();
        properties.setProperty("sessionId", world.sessionId().toString()); properties.setProperty("roomId", world.roomId());
        properties.setProperty("mapId", world.template().id()); properties.setProperty("worldName", world.worldName());
        properties.setProperty("type",type);properties.setProperty("editorUUID",owner.toString());
        if (layout != null) {
            properties.setProperty("storageLayout", RuntimeLayout.STORAGE_ID);
            properties.setProperty("namespace", layout.namespace());
        }
        properties.setProperty("createdAt", Instant.now().toString());properties.setProperty("status","ACTIVE");
        try (var output = Files.newOutputStream(world.runtimePath().resolve(MARKER), StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "BattleRoyale owned runtime world");
        }
    }
    public boolean dimensionStorage() { return layout != null; }
    public long worldSeed(Path directory) throws IOException {
        return layout == null ? LevelData.validate(directory.resolve("level.dat")) : LevelData.validateDimension(directory);
    }
    private String worldName(Path target) {
        return layout == null ? container.relativize(target).toString().replace('\\', '/')
                : layout.worldName(target.getFileName().toString());
    }
    private static void checkTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                noLinks(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                noLinks(file);
                if (!attrs.isRegularFile()) throw new IOException("Non-regular world entry: " + file);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    /** Rejects symbolic links, junctions and canonical redirects in all existing ancestors. */
    static void noLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            if (!Files.notExists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther() || !cursor.toRealPath().equals(cursor))
                    throw new IOException("Symbolic link/reparse/canonical redirect refused: " + cursor);
            }
        }
    }
    private static Path normalized(Path path) { return path.toAbsolutePath().normalize(); }
    private static boolean child(Path path, Path parent) { return path.startsWith(parent) && !path.equals(parent); }
    private static boolean overlap(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
}

