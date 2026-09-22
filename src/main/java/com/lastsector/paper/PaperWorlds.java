package com.lastsector.paper;
import com.lastsector.map.*;
import org.bukkit.*;
import net.kyori.adventure.util.TriState;
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
            Path active = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (active.startsWith(source) || source.startsWith(active))
                throw new IllegalStateException("Template must not be loaded as a server world: " + template.id());
        }
    }
    @Override public void load(GameWorld descriptor) {
        thread();
        validateTemplate(descriptor.template());
        Path actual = server.getWorldContainer().toPath().toAbsolutePath().normalize().resolve(descriptor.worldName()).normalize();
        if (!actual.equals(descriptor.runtimePath()) || !Files.isRegularFile(actual.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Prepared world directory mismatch/missing level.dat");
        if (server.getWorld(descriptor.worldName()) != null) throw new IllegalStateException("Runtime world name collision");
        for (World existing : server.getWorlds())
            if (existing.getWorldFolder().toPath().toAbsolutePath().normalize().equals(actual))
                throw new IllegalStateException("Runtime world path already loaded");
        World loaded = server.createWorld(new WorldCreator(descriptor.worldName(),
                new NamespacedKey("lastsector", descriptor.sessionId().toString())).keepSpawnLoaded(TriState.FALSE));
        if (loaded == null || !loaded.getWorldFolder().toPath().toAbsolutePath().normalize().equals(actual)
                || !loaded.getName().equals(descriptor.worldName()))
            throw new IllegalStateException("Paper did not load the expected runtime world");
        loaded.setAutoSave(true);
        if (loaded.getSeed() != descriptor.expectedSeed()) throw new IllegalStateException("Clone seed differs from template");
    }
    @Override public void unload(GameWorld descriptor) {
        thread();
        Path expected = descriptor.runtimePath().toAbsolutePath().normalize();
        for (World world : server.getWorlds()) {
            Path actual = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (!actual.equals(expected) && !world.getName().equals(descriptor.worldName())) continue;
            if (!actual.equals(expected) || world.equals(players.lobby()))
                throw new IllegalStateException("Refusing unload: world identity or lobby mismatch");
            if (!players.toLobby(world.getPlayers().stream().map(org.bukkit.entity.Player::getUniqueId).toList())
                    || !world.getPlayers().isEmpty()) throw new IllegalStateException("Players remain in runtime world");
            // Normal termination is destructive cleanup; crash recovery relies on enabled vanilla autosave.
            if (!server.unloadWorld(world, false)) throw new IllegalStateException("Paper refused to unload " + world.getName());
        }
        for (World world : server.getWorlds())
            if (world.getWorldFolder().toPath().toAbsolutePath().normalize().equals(expected))
                throw new IllegalStateException("World remains loaded after unload");
    }
}

