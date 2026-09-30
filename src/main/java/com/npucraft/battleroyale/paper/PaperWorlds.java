package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.map.*;
import org.bukkit.*;
import java.nio.file.*;
/** Uses only supported Paper/Bukkit APIs; no absolute paths are passed as world names. */
public final class PaperWorlds implements WorldGateway {
    private final Server server;
    private final PaperPlayers players;
    public PaperWorlds(Server server, PaperPlayers players) { this.server = server; this.players = players; }
    private void thread() { if (!server.isPrimaryThread()) throw new IllegalStateException("World API requires server thread"); }
    @Override public void validateTemplate(MapTemplate template) {
        thread();
        Path source = template.templatePath().toAbsolutePath().normalize();
        for (World world : server.getWorlds()) {
            Path active = world.getWorldPath().toAbsolutePath().normalize();
            if (active.startsWith(source) || source.startsWith(active))
                throw new IllegalStateException("Template must not be loaded as a server world: " + template.id());
        }
    }
    @Override public void load(GameWorld descriptor) {
        thread();
        validateTemplate(descriptor.template());
        Path actual = descriptor.runtimePath().toAbsolutePath().normalize();
        String namespace = actual.getParent().getFileName().toString();
        RuntimeLayout layout = new RuntimeLayout(server.getLevelDirectory(), namespace);
        String leaf = WorldFiles.leafName(descriptor.roomId(), descriptor.sessionId());
        NamespacedKey key = new NamespacedKey(namespace, leaf);
        if (!actual.equals(layout.runtimeRoot().resolve(leaf))
                || !descriptor.worldName().equals(layout.worldName(leaf))
                || !Files.isRegularFile(actual.resolve(LevelData.WORLD_GEN_SETTINGS), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Prepared Paper dimension identity/data mismatch");
        if (server.getWorld(descriptor.worldName()) != null || server.getWorld(key) != null)
            throw new IllegalStateException("Runtime world name/key collision");
        for (World existing : server.getWorlds())
            if (existing.getWorldPath().toAbsolutePath().normalize().equals(actual))
                throw new IllegalStateException("Runtime world path already loaded");
        // The supplied dimension owns its saved generator and seed; creator defaults are never substituted.
        World loaded = server.createWorld(WorldCreator.ofKey(key));
        if (loaded == null || !loaded.getWorldPath().toAbsolutePath().normalize().equals(actual)
                || !loaded.getKey().equals(key) || !loaded.getName().equals(descriptor.worldName()))
            throw new IllegalStateException("Paper did not load the expected runtime dimension");
        loaded.setAutoSave(true);
        if (loaded.getSeed() != descriptor.expectedSeed()) {
            server.unloadWorld(loaded, false);
            throw new IllegalStateException("Clone seed differs from template");
        }
    }
    @Override public void unload(GameWorld descriptor) {
        thread();
        Path expected = descriptor.runtimePath().toAbsolutePath().normalize();
        for (World world : server.getWorlds()) {
            Path actual = world.getWorldPath().toAbsolutePath().normalize();
            if (!actual.equals(expected) && !world.getName().equals(descriptor.worldName())) continue;
            if (!actual.equals(expected) || world.equals(players.lobby()))
                throw new IllegalStateException("Refusing unload: world identity or lobby mismatch");
            if (!players.toLobby(world.getPlayers().stream().map(org.bukkit.entity.Player::getUniqueId).toList())
                    || !world.getPlayers().isEmpty()) throw new IllegalStateException("Players remain in runtime world");
            // Normal termination is destructive cleanup; crash recovery relies on enabled vanilla autosave.
            if (!server.unloadWorld(world, false)) throw new IllegalStateException("Paper refused to unload " + world.getName());
        }
        for (World world : server.getWorlds())
            if (world.getWorldPath().toAbsolutePath().normalize().equals(expected))
                throw new IllegalStateException("World remains loaded after unload");
    }
}

