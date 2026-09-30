package com.npucraft.battleroyale.session;
import java.util.*;
/** In-memory registry. Confined to its owner thread; one registered session per room. */
public final class SessionManager {
    private final Map<UUID, GameSession> sessions = new LinkedHashMap<>();
    /** Registers a descriptor without starting it; duplicate ids or room ownership fail atomically. */
    public void register(GameSession session) {
        Objects.requireNonNull(session, "session");
        if (sessions.containsKey(session.sessionId()) || findByRoom(session.room().id()).isPresent())
            throw new IllegalArgumentException("Session id or room already registered: " + session.room().id());
        sessions.put(session.sessionId(), session);
    }
    /** Finds a registered session by its match identity. */
    public Optional<GameSession> find(UUID id) { return Optional.ofNullable(sessions.get(id)); }
    /** Finds the sole registered session for a room, if any. */
    public Optional<GameSession> findByRoom(String roomId) {
        return sessions.values().stream().filter(s -> s.room().id().equals(roomId)).findFirst();
    }
    /** Returns an immutable snapshot in registration order. */
    public List<GameSession> all() { return List.copyOf(sessions.values()); }
    /** Retires only a terminal match, never resetting or reusing its identity. */
    public void retire(GameSession session) {
        if (session.state() != GameState.CLEANUP) throw new IllegalStateException("Only CLEANUP sessions may be retired");
        if (!sessions.remove(session.sessionId(), session)) throw new IllegalStateException("Session is not registered");
    }
}
