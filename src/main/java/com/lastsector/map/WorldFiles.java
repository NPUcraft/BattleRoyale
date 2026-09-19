package com.lastsector.map;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** File-only world store. Every recursive operation is bounded, ownership checked and NOFOLLOW. */
public final class WorldFiles {
    public static final String MARKER = ".lastsector-runtime";
    private final Path dataRoot, runtimeRoot, container;
    private final List<Path> templates, protectedWorlds;
    private final FileCopier copier;
    /** Injectable IO fault boundary used by filesystem tests. */
    @FunctionalInterface public interface FileCopier { void copy(Path source, Path target) throws IOException; }
    public WorldFiles(Path dataRoot, Path runtimeRoot, Path container, List<Path> templates, List<Path> protectedWorlds) throws IOException {
        this(dataRoot, runtimeRoot, container, templates, protectedWorlds,
                (source, target) -> Files.copy(source, target, LinkOption.NOFOLLOW_LINKS));
    }
    public WorldFiles(Path dataRoot, Path runtimeRoot, Path container, List<Path> templates, List<Path> protectedWorlds, FileCopier copier) throws IOException {
        this.copier = copier;
        this.dataRoot = normalized(dataRoot); this.runtimeRoot = normalized(runtimeRoot); this.container = normalized(container);
        this.templates = templates.stream().map(WorldFiles::normalized).toList();
        this.protectedWorlds = protectedWorlds.stream().map(WorldFiles::normalized).toList();
        noLinks(this.dataRoot); noLinks(this.container); noLinks(this.runtimeRoot);
        if (!child(this.runtimeRoot, this.dataRoot) || !child(this.runtimeRoot, this.container))
            throw new IOException("Runtime root must be a strict child of plugin data and the Paper world container: " + runtimeRoot);
        for (Path path : this.templates) if (overlap(path, this.runtimeRoot)) throw new IOException("Runtime root overlaps template: " + path);
        for (Path path : this.protectedWorlds) if (overlap(path, this.runtimeRoot)) throw new IOException("Runtime root overlaps server world: " + path);
    }
    /** Full UUID is retained; only a bounded sanitized room fragment contributes to the leaf name. */
    public static String leafName(String room, UUID session) {
        String safe = room.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (safe.length() > 24) safe = safe.substring(0, 24);
        if (safe.isEmpty()) safe = "room";
        return "ls_" + safe + "_" + session.toString().replace("-", "");
    }
    public GameWorld descriptor(UUID session, String room, MapTemplate template) {
        Path target = runtimeRoot.resolve(leafName(room, session));
        String name = container.relativize(target).toString().replace('\\', '/');
        return new GameWorld(session, room, name, target, template);
    }
    /** Validates complete source first. Failed partial copies keep a marker unless cleanup succeeds. */
    public GameWorld copy(UUID session, String room, MapTemplate template) throws IOException {
        Path source = validateTemplate(template);
        GameWorld descriptor = descriptor(session, room, template);
        GameWorld world = new GameWorld(session, room, descriptor.worldName(), descriptor.runtimePath(), template,
                LevelData.validate(source.resolve("level.dat")));
        validateTarget(world);
        Files.createDirectories(runtimeRoot); noLinks(runtimeRoot);
        Files.createDirectory(world.runtimePath()); // Never merge with or overwrite an existing directory.
        try {
            writeMarker(world);
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
                    if (!excluded(relative)) {
                        Path destination = world.runtimePath().resolve(relative);
                        noLinks(destination.getParent());
                        copier.copy(file, destination);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            LevelData.validate(world.runtimePath().resolve("level.dat"));
            checkTree(world.runtimePath());
            return world;
        } catch (IOException | RuntimeException failure) {
            try { delete(world, true); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private boolean excluded(Path relative) {
        String name = relative.getFileName().toString();
        return Set.of("session.lock", "uid.dat", "playerdata", "stats", "advancements", MARKER).contains(name);
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
        LevelData.validate(source.resolve("level.dat"));
        return source;
    }
    /** Call only with a server-thread unload acknowledgement, or for a clone never offered to Bukkit. */
    public void delete(GameWorld world, boolean confirmedUnloaded) throws IOException {
        if (!confirmedUnloaded) throw new IOException("Refusing deletion without unload confirmation: " + world.runtimePath());
        validateTarget(world);
        Path target = world.runtimePath();
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return;
        validateMarker(world);
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
                catch (IOException error) { writeMarker(world); throw error; }
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
                || !world.worldName().equals(container.relativize(target).toString().replace('\\', '/')))
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
        noLinks(world.runtimePath().resolve("level.dat"));
        if (!Files.isRegularFile(world.runtimePath().resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Clone level.dat is no longer present");
    }
    private void validateMarker(GameWorld world) throws IOException {
        Path marker = world.runtimePath().resolve(MARKER); noLinks(marker);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 16384)
            throw new IOException("Missing/invalid LastSector ownership marker: " + marker);
        Properties properties = new Properties();
        try (var input = Files.newInputStream(marker)) { properties.load(input); }
        if (!world.sessionId().toString().equals(properties.getProperty("sessionId"))
                || !world.roomId().equals(properties.getProperty("roomId"))
                || !world.template().id().equals(properties.getProperty("mapId"))
                || !world.worldName().equals(properties.getProperty("worldName")))
            throw new IOException("Ownership marker does not match this session: " + marker);
        try { Instant.parse(properties.getProperty("createdAt")); }
        catch (RuntimeException error) { throw new IOException("Invalid ownership timestamp", error); }
    }
    private void writeMarker(GameWorld world) throws IOException {
        noLinks(world.runtimePath());
        Properties properties = new Properties();
        properties.setProperty("sessionId", world.sessionId().toString()); properties.setProperty("roomId", world.roomId());
        properties.setProperty("mapId", world.template().id()); properties.setProperty("worldName", world.worldName());
        properties.setProperty("createdAt", Instant.now().toString());
        try (var output = Files.newOutputStream(world.runtimePath().resolve(MARKER), StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "LastSector owned runtime world");
        }
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

