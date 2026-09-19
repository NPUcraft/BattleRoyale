package com.lastsector.service;

import com.lastsector.config.ConfigurationSnapshot;
import com.lastsector.map.*;
import com.lastsector.room.RoomDefinition;
import com.lastsector.session.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Server-thread room orchestration. UUID membership, timers and operation generations are per session. */
public final class RoomRuntimeService implements AutoCloseable {
    private final Supplier<ConfigurationSnapshot> configuration;
    private final SessionManager sessions;
    private final GameScheduler scheduler;
    private final MapSelector selector;
    private final WorldProvider worlds;
    private final PlayerGateway players;
    private final Clock clock;
    private final MatchLifecycle matches;
    private final Map<UUID, UUID> memberships = new HashMap<>();
    private final Map<UUID, Countdown> countdowns = new HashMap<>();
    private final Map<UUID, UUID> operations = new HashMap<>();
    private volatile boolean closed;

    public RoomRuntimeService(Supplier<ConfigurationSnapshot> configuration, SessionManager sessions,
            GameScheduler scheduler, MapSelector selector, WorldProvider worlds, PlayerGateway players, Clock clock, MatchLifecycle matches) {
        this.configuration = configuration; this.sessions = sessions; this.scheduler = scheduler;
        this.selector = selector; this.worlds = worlds; this.players = players; this.clock = clock;
        this.matches = matches;
    }
    public List<RoomDefinition> rooms() { return configuration.get().rooms(); }
    public Optional<GameSession> session(String room) { return sessions.findByRoom(room); }
    public int remaining(GameSession session) {
        Countdown countdown = countdowns.get(session.sessionId());
        return countdown == null ? -1 : countdown.remaining;
    }
    public boolean canReload() { return !closed && memberships.isEmpty() && sessions.all().isEmpty() && !worlds.busy(); }

    /** Joins an existing waiting match, or creates the next unique match identity lazily. */
    public void join(UUID player, String roomId) {
        checkOpen();
        Objects.requireNonNull(player, "player");
        if (memberships.containsKey(player)) throw new IllegalStateException("Already in room");
        RoomDefinition room = rooms().stream().filter(r -> r.id().equals(roomId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Room does not exist: " + roomId));
        GameSession session = session(roomId).orElseGet(() -> {
            var created = GameSession.waiting(UUID.randomUUID(), room, clock.instant());
            sessions.register(created); return created;
        });
        session.join(player);
        memberships.put(player, session.sessionId());
        players.notify(List.of(player), "joined", roomId);
        considerCountdown(session);
    }
    /** Stable tie breaker is configuration order; equal counts never replace the earlier candidate. */
    public String autojoin(UUID player) {
        checkOpen();
        if (memberships.containsKey(player)) throw new IllegalStateException("Already in room");
        RoomDefinition best = null; int bestCount = -1;
        for (RoomDefinition room : rooms()) {
            GameSession session = session(room.id()).orElse(null);
            int count = session == null ? 0 : session.players().size();
            if ((session == null || session.joinable()) && count < room.maxPlayers() && count > bestCount) {
                best = room; bestCount = count;
            }
        }
        if (best == null) throw new IllegalStateException("No room available");
        join(player, best.id()); return best.id();
    }
    public void leave(UUID player) {
        GameSession session = membership(player);
        session.leave(player); memberships.remove(player);
        players.notify(List.of(player), "left", session.room().id());
        considerCountdown(session);
        if (session.players().isEmpty()) { session.transition(GameState.CLEANUP); retire(session); }
    }
    public void disconnected(UUID player) {
        matches.disconnected(player);
        UUID sessionId = memberships.get(player);
        if (sessionId == null) return;
        GameSession session = sessions.find(sessionId).orElseThrow();
        if (session.joinable()) leave(player);
        else session.disconnected(player);
    }
    private GameSession membership(UUID player) {
        UUID id = memberships.get(player);
        if (id == null) throw new IllegalStateException("You are not in a room");
        return sessions.find(id).orElseThrow();
    }
    private void considerCountdown(GameSession session) {
        if (session.players().size() < session.room().minPlayers()) {
            if (session.state() == GameState.COUNTDOWN) {
                cancelCountdown(session); session.transition(GameState.WAITING);
                players.notify(session.players().keySet(), "countdown-cancelled");
            }
        } else if (session.state() == GameState.WAITING) {
            session.transition(GameState.COUNTDOWN);
            Countdown countdown = new Countdown((int) session.room().countdownDuration().getSeconds());
            countdowns.put(session.sessionId(), countdown);
            countdown.task = scheduler.repeat(20, () -> tick(session, countdown));
            players.notify(session.players().keySet(), "countdown-started", countdown.remaining);
        }
    }
    private void tick(GameSession session, Countdown countdown) {
        if (closed || countdowns.get(session.sessionId()) != countdown || session.state() != GameState.COUNTDOWN) return;
        if (session.players().size() < session.room().minPlayers()) { considerCountdown(session); return; }
        countdown.remaining--;
        if (countdown.remaining <= 0) prepare(session);
        else if (Set.of(30, 20, 10, 5, 4, 3, 2, 1).contains(countdown.remaining))
            players.notify(session.players().keySet(), "countdown-remaining", countdown.remaining);
    }
    private void cancelCountdown(GameSession session) {
        Countdown timer = countdowns.remove(session.sessionId());
        if (timer != null && timer.task != null) timer.task.cancel();
    }
    public void debugStart(String room) {
        checkOpen();
        GameSession session = session(room).orElseThrow(() -> new IllegalStateException("Room must have at least one player"));
        if (!session.joinable() || session.players().isEmpty()) throw new IllegalStateException("Room cannot be started in its current state");
        prepare(session);
    }
    private void prepare(GameSession session) {
        cancelCountdown(session);
        UUID token = UUID.randomUUID();
        operations.put(session.sessionId(), token);
        try {
            session.prepare(selector.select(session.room(), configuration.get().maps()));
            players.notify(session.players().keySet(), "preparing", session.selectedMap().orElseThrow().id());
            worlds.prepare(session.sessionId(), session.room().id(), session.selectedMap().orElseThrow(),
                    () -> current(session, token)).whenComplete((world, error) -> {
                if (closed) return; // Provider owns shutdown cleanup; no off-thread session mutation.
                if (!current(session, token)) {
                    if (world != null) worlds.release(world).whenComplete((ignored, failure) -> finish(session, failure));
                    else finish(session, error);
                    return;
                }
                if (error != null) { abort(session, error); return; }
                try {
                    session.starting(world);
                    matches.start(session, () -> {
                        if (closed || session.state() != GameState.STARTING || !token.equals(operations.get(session.sessionId()))) return;
                        try {
                            session.transition(GameState.RUNNING);
                            matches.running(session, failure -> abort(session, failure));
                            players.notify(session.players().keySet(), "started", world.worldName());
                        } catch (Exception failure) { abort(session, failure); }
                    }, failure -> { if (!closed && session.state() == GameState.STARTING) abort(session, failure); });
                } catch (Exception failure) { abort(session, failure); }
            });
        } catch (Exception error) { abort(session, error); }
    }
    private boolean current(GameSession session, UUID token) {
        return !closed && sessions.find(session.sessionId()).orElse(null) == session
                && session.state() == GameState.PREPARING && token.equals(operations.get(session.sessionId()));
    }
    public void debugEnd(String room) {
        GameSession session = session(room).orElseThrow(() -> new IllegalArgumentException("No session for room: " + room));
        if (!Set.of(GameState.PREPARING, GameState.STARTING, GameState.RUNNING).contains(session.state()))
            throw new IllegalStateException("Only preparing, starting or running sessions may be ended");
        end(session);
    }
    private void abort(GameSession session, Throwable error) {
        if (closed || session.state() == GameState.CLEANUP) return;
        players.error("Session preparation failed: " + session.sessionId(), error);
        players.notify(session.players().keySet(), "preparation-failed", error.getMessage());
        end(session);
        if (session.gameWorld().isEmpty()) finish(session, null);
    }
    private void end(GameSession session) {
        GameState previous = session.state();
        operations.remove(session.sessionId());
        cancelCountdown(session);
        if (session.state() == GameState.RUNNING) session.transition(GameState.ENDING);
        if (session.state() != GameState.CLEANUP) session.transition(GameState.CLEANUP);
        players.notify(session.players().keySet(), "ended");
        try {
            if (!players.toLobby(session.players().keySet()))
                players.error("Some players could not return to lobby; unload must refuse occupied worlds", new IllegalStateException("Teleport failed"));
        } catch (Exception failure) { players.error("Lobby return failed", failure); }
        if (session.gameWorld().isPresent()) {
            matches.stop(session, () -> worlds.release(session.gameWorld().orElseThrow()).whenComplete((ignored, error) -> finish(session, error)));
        } else if (previous != GameState.PREPARING) finish(session, null);
        // Pending prepare completes only after cancelled copy cleanup; keep CLEANUP until then.
    }
    private void finish(GameSession session, Throwable error) {
        if (closed) return;
        if (error != null && !(unwrap(error) instanceof java.util.concurrent.CancellationException))
            players.error("World cleanup/preparation failed for " + session.sessionId(), unwrap(error));
        if (sessions.find(session.sessionId()).orElse(null) != session) return;
        if (session.state() != GameState.CLEANUP) session.transition(GameState.CLEANUP);
        retire(session);
    }
    private Throwable unwrap(Throwable error) { return error.getCause() == null ? error : error.getCause(); }
    private void retire(GameSession session) {
        operations.remove(session.sessionId());
        session.players().keySet().forEach(id -> memberships.remove(id, session.sessionId()));
        sessions.retire(session);
    }
    private void checkOpen() { if (closed) throw new IllegalStateException("LastSector is stopping"); }
    @Override public void close() {
        closed = true;
        matches.close();
        countdowns.values().forEach(timer -> timer.task.cancel()); countdowns.clear(); operations.clear();
        for (GameSession session : sessions.all()) {
            try { players.toLobby(session.players().keySet()); }
            catch (Exception error) { players.error("Shutdown lobby return failed", error); }
        }
        worlds.close();
    }
    private static final class Countdown {
        private int remaining;
        private GameScheduler.Task task;
        private Countdown(int remaining) { this.remaining = remaining; }
    }
}

