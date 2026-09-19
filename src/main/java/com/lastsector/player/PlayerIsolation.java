package com.lastsector.player;

import java.util.*;
import java.util.function.BiConsumer;

/** Server-thread transaction ledger. Pending restores outlive retired sessions. */
public final class PlayerIsolation<S,L> {
    public interface Gateway<S,L> {
        S capture(UUID player);
        void apply(UUID player,L loadout);
        boolean restore(UUID player,S snapshot);
    }
    private final Gateway<S,L> gateway;
    private final BiConsumer<UUID,RuntimeException> errors;
    private final Map<UUID,Map<UUID,S>> matches = new HashMap<>();
    private final Map<UUID,S> pending = new HashMap<>();
    public PlayerIsolation(Gateway<S,L> gateway,BiConsumer<UUID,RuntimeException> errors) { this.gateway=gateway; this.errors=errors; }
    public void apply(UUID session,List<UUID> players,L loadout) {
        if (matches.containsKey(session)) throw new IllegalStateException("Loadout already applied for session");
        Map<UUID,S> snapshots = new LinkedHashMap<>();
        for (UUID player : players) {
            if (blocked(player) || matches.values().stream().anyMatch(m -> m.containsKey(player))) throw new IllegalStateException("Player state is already isolated: " + player);
            snapshots.put(player,gateway.capture(player));
        }
        matches.put(session,snapshots); // Journal every original before the first destructive write.
        try { for (UUID player : players) gateway.apply(player,loadout); }
        catch (RuntimeException failure) { end(session); throw failure; }
    }
    public void end(UUID session) {
        Map<UUID,S> snapshots=matches.remove(session); if (snapshots==null) return;
        snapshots.forEach((player,snapshot)-> { pending.put(player,snapshot); retry(player); });
    }
    public boolean retry(UUID player) {
        S snapshot=pending.get(player); if (snapshot==null) return true;
        try { if (gateway.restore(player,snapshot)) { pending.remove(player); return true; } }
        catch (RuntimeException error) { errors.accept(player,error); }
        return false;
    }
    public boolean blocked(UUID player) { return pending.containsKey(player); }
    public int pendingCount() { return pending.size(); }
    public void close() { List.copyOf(matches.keySet()).forEach(this::end); }
}
