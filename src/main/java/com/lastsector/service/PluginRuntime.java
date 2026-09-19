package com.lastsector.service;

import com.lastsector.config.ConfigurationSnapshot;
import com.lastsector.map.*;
import com.lastsector.paper.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.Executors;

/** Paper composition root. Creates replacements before publishing reloads; owns shutdown. */
public final class PluginRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final FoundationService foundation;
    private final MessageService messages;
    private RoomRuntimeService rooms;
    private PaperMatches matches;
    private OnDemandWorldProvider provider;
    public PaperMatches matches() { return matches; }
    public PluginRuntime(JavaPlugin plugin, FoundationService foundation, MessageService messages) {
        this.plugin = plugin; this.foundation = foundation; this.messages = messages;
    }
    public RoomRuntimeService rooms() { return rooms; }
    public void reload() {
        if (rooms != null && !rooms.canReload())
            throw new IllegalStateException("Cannot reload LastSector while rooms or game sessions are active.");
        RoomRuntimeService[] prepared = new RoomRuntimeService[1];
        foundation.reload(candidate -> prepared[0] = create(candidate));
        RoomRuntimeService old = rooms;
        rooms = prepared[0];
        if (old != null) old.close();
    }
    private RoomRuntimeService create(ConfigurationSnapshot configuration) {
        var server = plugin.getServer();
        var players = new PaperPlayers(server, configuration.settings().lobbyWorld(), messages);
        WorldFiles files;
        try {
            files = new WorldFiles(plugin.getDataFolder().toPath(), configuration.settings().runtimeDirectory(),
                    server.getWorldContainer().toPath(), configuration.maps().stream().map(MapTemplate::templatePath).toList(),
                    server.getWorlds().stream().map(world -> world.getWorldFolder().toPath()).toList());
        } catch (IOException error) { throw new IllegalStateException("Invalid runtime world paths: " + error.getMessage(), error); }
        var scheduler = new PaperScheduler(plugin);
        var worker = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "LastSector-world-io");
            thread.setUncaughtExceptionHandler((t, error) -> messages.runtimeError("World IO uncaught failure", error));
            return thread;
        });
        OnDemandWorldProvider provider;
        try { provider = new OnDemandWorldProvider(files, new PaperWorlds(server, players), scheduler, worker, messages::runtimeError); }
        catch (RuntimeException error) { worker.shutdown(); throw error; }
        var matches = new PaperMatches(plugin, configuration, scheduler, com.lastsector.zone.GameClock.system(), new java.util.Random(), players);
        var result = new RoomRuntimeService(() -> foundation.state().configuration(), foundation.sessions(),
                scheduler, MapSelector.random(new java.util.Random()), provider, players, Clock.systemUTC(), matches);
        this.matches = matches;
        this.provider = provider;
        return result;
    }
    @Override public void close() {
        if (rooms != null) {
            try { rooms.close(); } catch (Exception error) { messages.runtimeError("Runtime shutdown failed", error); }
        }
        if (provider != null) {
            try {
                if (!provider.awaitFileShutdown(30, java.util.concurrent.TimeUnit.SECONDS))
                    plugin.getLogger().warning("World IO shutdown did not drain within the shutdown boundary; inspect retained runtime markers.");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        foundation.close();
    }
}

