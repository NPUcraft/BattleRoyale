package com.npucraft.lastsector.session;
import com.npucraft.lastsector.room.RoomDefinition;
import com.npucraft.lastsector.map.*;
import com.npucraft.lastsector.player.*;
import com.npucraft.lastsector.team.GameTeam;
import java.time.Instant;
import java.util.*;

/** One match, mutated only on its owning server thread through guarded lifecycle methods. */
public final class GameSession {
    private final UUID sessionId;
    private final RoomDefinition room;
    private final Instant createdAt;
    private GameState state = GameState.WAITING;
    private MatchOutcome outcome;
    private final Map<UUID,GameTeam> teams=new LinkedHashMap<>();
    private final Set<UUID> offlineCombatants=new HashSet<>();
    public boolean combatActive(UUID id) {
        var p=players.get(id);return p!=null && (p.state()==PlayerState.ALIVE || p.state()==PlayerState.DISCONNECTED && offlineCombatants.contains(id));
    }
    public long activeCount() {return players.keySet().stream().filter(this::combatActive).count();}
    public long activeTeamCount() {return teams.values().stream().filter(t->t.playerIds().stream().anyMatch(this::combatActive)).count();}
    public boolean sameTeam(UUID a,UUID b) {
        var first=players.get(a);var second=players.get(b);return first!=null && second!=null && first.teamId().isPresent() && first.teamId().equals(second.teamId());
    }
    public void offlineCombatant(UUID id,boolean live) {if(!players.containsKey(id))throw new IllegalArgumentException("Unknown player");if(live)offlineCombatants.add(id);else offlineCombatants.remove(id);}
    public boolean reconnect(UUID id) {if(state!=GameState.RUNNING || !combatActive(id) || players.get(id).state()!=PlayerState.DISCONNECTED)return false;replaceState(id,PlayerState.ALIVE);offlineCombatants.remove(id);return true;}
    public void spectating(UUID id,boolean watching) {
        var p=players.get(id);if(p==null || (p.state()!=PlayerState.ELIMINATED && p.state()!=PlayerState.SPECTATING))throw new IllegalStateException("Not eliminated");
        replaceState(id,watching?PlayerState.SPECTATING:PlayerState.ELIMINATED);
    }
    private void replaceState(UUID id,PlayerState state) {var p=players.get(id);players.put(id,new GamePlayer(id,state,p.teamId(),p.kills(),p.assists()));}
    public Optional<MatchOutcome> outcome() { return Optional.ofNullable(outcome); }
    public void outcome(MatchOutcome value) {
        if(state!=GameState.RUNNING || outcome!=null) throw new IllegalStateException("Outcome already decided or session not running");
        if(!players.keySet().containsAll(value.winnerIds())) throw new IllegalArgumentException("Unknown winner");
        outcome=Objects.requireNonNull(value); transition(GameState.ENDING);
    }
    public boolean eliminate(UUID id) {
        GamePlayer player=players.get(id);
        if(state!=GameState.RUNNING || player==null || !combatActive(id)) return false;
        offlineCombatants.remove(id);
        players.put(id,new GamePlayer(id,PlayerState.ELIMINATED,player.teamId(),player.kills(),player.assists())); return true;
    }
    public void credit(UUID id,boolean kill) {
        GamePlayer p=Objects.requireNonNull(players.get(id));
        players.put(id,new GamePlayer(id,p.state(),p.teamId(),p.kills()+(kill?1:0),p.assists()+(kill?0:1)));
    }
    private MapTemplate selectedMap;
    private GameWorld gameWorld;
    private com.npucraft.lastsector.zone.Zone initialZone;
    private com.npucraft.lastsector.zone.ZoneRuntime zone;
    private com.npucraft.lastsector.combat.ProtectionWindow protection;
    public Optional<com.npucraft.lastsector.zone.Zone> initialZone() { return Optional.ofNullable(initialZone); }
    public Optional<com.npucraft.lastsector.zone.ZoneRuntime> zone() { return Optional.ofNullable(zone); }
    public Optional<com.npucraft.lastsector.combat.ProtectionWindow> protection() { return Optional.ofNullable(protection); }
    public void initialZone(com.npucraft.lastsector.zone.Zone value) {
        if (state != GameState.STARTING || initialZone != null) throw new IllegalStateException("Initial zone can only be assigned once at STARTING");
        initialZone = Objects.requireNonNull(value);
    }
    public void runningZone(com.npucraft.lastsector.zone.ZoneRuntime value, com.npucraft.lastsector.combat.ProtectionWindow window) {
        if (state != GameState.RUNNING || zone != null || !value.initial().equals(initialZone)) throw new IllegalStateException("Invalid running zone");
        zone = Objects.requireNonNull(value); protection = Objects.requireNonNull(window);
    }
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();
    private GameSession(UUID id, RoomDefinition room, Instant createdAt) {
        this.sessionId = Objects.requireNonNull(id); this.room = Objects.requireNonNull(room);
        this.createdAt = Objects.requireNonNull(createdAt);
    }
    public static GameSession waiting(UUID id, RoomDefinition room, Instant createdAt) { return new GameSession(id, room, createdAt); }
    public static GameSession recovered(com.npucraft.lastsector.recovery.SessionRecoverySnapshot saved,RoomDefinition room,GameWorld world) {
        var s=new GameSession(saved.sessionId(),room,Instant.now());s.selectedMap=world.template();s.gameWorld=world;s.state=GameState.valueOf(saved.gameState());
        if(s.state!=GameState.RUNNING && s.state!=GameState.ENDING)throw new IllegalArgumentException("Unrecoverable session phase");
        for(var t:saved.teams())s.teams.put(t.id(),new GameTeam(t.id(),t.members(),t.index()));
        for(var p:saved.participants()){var state=PlayerState.valueOf(p.state());if(state==PlayerState.ALIVE)state=PlayerState.DISCONNECTED;if(state==PlayerState.SPECTATING)state=PlayerState.ELIMINATED;s.players.put(p.id(),new GamePlayer(p.id(),state,Optional.of(p.team()),p.kills(),p.assists()));}
        s.initialZone=saved.zone().initial();var o=saved.outcome();if(o!=null)s.outcome=new MatchOutcome(o.players(),o.tie(),o.reason(),0,o.tick(),o.teams());return s;
    }
    public void recoveredZone(com.npucraft.lastsector.zone.ZoneRuntime zone,com.npucraft.lastsector.combat.ProtectionWindow protection){if(this.zone!=null || !zone.initial().equals(initialZone))throw new IllegalStateException("Already initialized");this.zone=zone;this.protection=protection;}
    public UUID sessionId() { return sessionId; }
    public RoomDefinition room() { return room; }
    public Instant createdAt() { return createdAt; }
    public GameState state() { return state; }
    public Optional<MapTemplate> selectedMap() { return Optional.ofNullable(selectedMap); }
    public Optional<GameWorld> gameWorld() { return Optional.ofNullable(gameWorld); }
    public Map<UUID, GamePlayer> players() { return Collections.unmodifiableMap(new LinkedHashMap<>(players)); }
    public Map<UUID, GameTeam> teams() { return Collections.unmodifiableMap(new LinkedHashMap<>(teams)); }
    /** Participant spectators only; external presences belong to SpectatorRegistry. */
    public Set<UUID> spectators() { return players.values().stream().filter(p->p.state()==PlayerState.SPECTATING).map(GamePlayer::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
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
    /** Preserves frozen team membership; the session body registry owns reconnect eligibility. */
    public void disconnected(UUID id) {
        GamePlayer old = players.get(id);
        if (old != null && old.state()!=PlayerState.ELIMINATED && old.state()!=PlayerState.SPECTATING) players.put(id, new GamePlayer(id, PlayerState.DISCONNECTED, old.teamId(), old.kills(), old.assists()));
    }
    /** Selects exactly once and enters PREPARING; selection must belong to the room pool. */
    public void prepare(MapTemplate map) {
        prepare(map,new java.util.Random());
    }
    public void prepare(MapTemplate map,java.util.random.RandomGenerator random) {
        if (!joinable() || players.isEmpty() || selectedMap != null) throw new IllegalStateException("Cannot prepare this session");
        if (!room.mapPool().contains(map.id())) throw new IllegalArgumentException("Selected map is outside room pool");
        selectedMap = map;
        for(var team:com.npucraft.lastsector.team.TeamAssignment.assign(sessionId,com.npucraft.lastsector.team.TeamAssignmentInput.automatic(players.keySet(),room.teamSize()),random)) {
            teams.put(team.teamId(),team);
            for(UUID id:team.playerIds()){var p=players.get(id);players.put(id,new GamePlayer(id,p.state(),Optional.of(team.teamId()),p.kills(),p.assists()));}
        }
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

