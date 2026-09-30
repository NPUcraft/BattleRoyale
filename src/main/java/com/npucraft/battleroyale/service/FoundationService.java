package com.npucraft.battleroyale.service;

import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.room.RoomManager;
import com.npucraft.battleroyale.map.MapRegistry;
import com.npucraft.battleroyale.session.SessionManager;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Owner-thread composition root. Reload validates a complete candidate before one reference swap.
 * Any registered session blocks reload until a later milestone defines safe migration.
 */
public final class FoundationService implements AutoCloseable {
    private final Supplier<ConfigurationSnapshot> loader;
    private final SessionManager sessions;
    private State state;
    public FoundationService(Supplier<ConfigurationSnapshot> loader, SessionManager sessions) {
        this.loader = Objects.requireNonNull(loader);
        this.sessions = Objects.requireNonNull(sessions);
    }
    /** Validates and publishes a fresh snapshot; any registered session blocks this operation. */
    public void reload() {
        reload(candidate -> {});
    }
    /** Validates/prepares adapter resources before publishing the new immutable configuration. */
    public void reload(Consumer<ConfigurationSnapshot> beforePublish) {
        if (!sessions.all().isEmpty()) throw new IllegalStateException("Cannot reload BattleRoyale while rooms or game sessions are active.");
        ConfigurationSnapshot candidate = loader.get();
        RoomManager rooms = new RoomManager();
        MapRegistry maps = new MapRegistry();
        candidate.rooms().forEach(rooms::register);
        candidate.maps().forEach(maps::register);
        beforePublish.accept(candidate);
        state = new State(candidate, rooms, maps);
    }
    public State state() {
        if (state == null) throw new IllegalStateException("Foundation has not been initialized.");
        return state;
    }
    public SessionManager sessions() { return sessions; }
    /** M1 holds no worlds, tasks or database connections. Releases the configuration reference. */
    @Override public void close() { state = null; }
    /** The three values are published together; callers must not retain registries across reload. */
    public record State(ConfigurationSnapshot configuration, RoomManager rooms, MapRegistry maps) {}
}
