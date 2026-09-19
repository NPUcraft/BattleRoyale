package com.lastsector.session;
import com.lastsector.room.RoomDefinition;
import com.lastsector.map.*;
import com.lastsector.player.*;
import com.lastsector.team.GameTeam;
import java.time.Instant;
import java.util.*;

/** One match, mutated only on its owning server thread through guarded lifecycle methods. */
public final class GameSession {
    private final UUID sessionId;
    private final RoomDefinition room;
    private final Instant createdAt;
    private GameState state = GameState.WAITING;
    private MapTemplate selectedMap;
    private GameWorld gameWorld;
    private com.lastsector.zone.Zone initialZone;
    private com.lastsector.zone.ZoneRuntime zone;
    private com.lastsector.combat.ProtectionWindow protection;
    public Optional<com.lastsector.zone.Zone> initialZone() { return Optional.ofNullable(initialZone); }
    public Optional<com.lastsector.zone.ZoneRuntime> zone() { return Optional.ofNullable(zone); }
    public Optional<com.lastsector.combat.ProtectionWindow> protection() { return Optional.ofNullable(protection); }
    public void initialZone(com.lastsector.zone.Zone value) {
        if (state != GameState.STARTING || initialZone != null) throw new IllegalStateException("Initial zone can only be assigned once at STARTING");
        initialZone = Objects.requireNonNull(value);
    }
    public void runningZone(com.lastsector.zone.ZoneRuntime value, com.lastsector.combat.ProtectionWindow window) {
        if (state != GameState.RUNNING || zone != null || !value.initial().equals(initialZone)) throw new IllegalStateException("Invalid running zone");
        zone = Objects.requireNonNull(value); protection = Objects.requireNonNull(window);
    }
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();
    private GameSession(UUID id, RoomDefinition room, Instant createdAt) {
        this.sessionId = Objects.requireNonNull(id); this.room = Objects.requireNonNull(room);
        this.createdAt = Objects.requireNonNull(createdAt);
    }
    public static GameSession waiting(UUID id, RoomDefinition room, Instant createdAt) { return new GameSession(id, room, createdAt); }
    public UUID sessionId() { return sessionId; }
    public RoomDefinition room() { return room; }
    public Instant createdAt() { return createdAt; }
    public GameState state() { return state; }
    public Optional<MapTemplate> selectedMap() { return Optional.ofNullable(selectedMap); }
    public Optional<GameWorld> gameWorld() { return Optional.ofNullable(gameWorld); }
    public Map<UUID, GamePlayer> players() { return Collections.unmodifiableMap(new LinkedHashMap<>(players)); }
    public Map<UUID, GameTeam> teams() { return Map.of(); }
    public Set<UUID> spectators() { return Set.of(); }
    public boolean joinable() { return state == GameState.WAITING || state == GameState.COUNTDOWN; }
    /** Adds a waiting UUID; cross-room uniqueness is enforced by RoomRuntimeService. */
    public void join(UUID id) {
        if (!joinable()) throw new IllegalStateException("Room is not joinable");
        if (players.containsKey(id)) throw new IllegalStateException("Already in room");
        if (players.size() >= room.maxPlayers()) throw new IllegalStateException("Room is full");
        players.put(id, new GamePlayer(id, PlayerState.WAITING, Optional.empty(), 0, 0));
    }
    public void leave(UUID id) {
        if (!joinable()) throw new IllegalStateException("Leaving is only allowed while waiting or counting down");
        if (players.remove(id) == null) throw new IllegalStateException("Player is not in this room");
    }
    /** M2 TEMPORARY BEHAVIOR: retains active disconnected UUIDs without reconnect recovery. */
    public void disconnected(UUID id) {
        GamePlayer old = players.get(id);
        if (old != null) players.put(id, new GamePlayer(id, PlayerState.DISCONNECTED, old.teamId(), old.kills(), old.assists()));
    }
    /** Selects exactly once and enters PREPARING; selection must belong to the room pool. */
    public void prepare(MapTemplate map) {
        if (!joinable() || players.isEmpty() || selectedMap != null) throw new IllegalStateException("Cannot prepare this session");
        if (!room.mapPool().contains(map.id())) throw new IllegalArgumentException("Selected map is outside room pool");
        selectedMap = map;
        transition(GameState.PREPARING);
    }
    /** Attaches only this session's selected-map resource and advances to staging. */
    public void starting(GameWorld world) {
        if (state != GameState.PREPARING || gameWorld != null || !world.sessionId().equals(sessionId) || !world.roomId().equals(room.id())
                || !world.template().equals(selectedMap)) throw new IllegalStateException("World ownership mismatch");
        gameWorld = world;
        transition(GameState.STARTING);
    }
    /** Enforces the M2 transition graph. CLEANUP is terminal. */
    public void transition(GameState next) {
        boolean allowed = switch (state) {
            case WAITING -> next == GameState.COUNTDOWN || next == GameState.PREPARING || next == GameState.CLEANUP;
            case COUNTDOWN -> next == GameState.WAITING || next == GameState.PREPARING || next == GameState.CLEANUP;
            case PREPARING -> next == GameState.STARTING || next == GameState.CLEANUP;
            case STARTING -> next == GameState.RUNNING || next == GameState.CLEANUP;
            case RUNNING -> next == GameState.ENDING;
            case ENDING -> next == GameState.CLEANUP;
            case CLEANUP -> false;
        };
        if (!allowed || (next == GameState.PREPARING && selectedMap == null)
                || ((next == GameState.STARTING || next == GameState.RUNNING) && gameWorld == null))
            throw new IllegalStateException("Invalid state transition: " + state + " -> " + next);
        state = next;
        if (next == GameState.RUNNING) players.replaceAll((id, player) ->
                player.state() == PlayerState.DISCONNECTED ? player :
                        new GamePlayer(id, PlayerState.ALIVE, player.teamId(), player.kills(), player.assists()));
    }
}

