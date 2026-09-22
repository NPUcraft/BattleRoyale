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
    public interface Durability<S> {
        java.util.concurrent.CompletionStage<Void> store(UUID session,Map<UUID,S> snapshots);
        void pending(UUID player);
    }
    private Durability<S> durability;
    private final Set<UUID> barriers=new HashSet<>();
    public void durability(Durability<S> durability){this.durability=durability;}
    public boolean ready(UUID session){return matches.containsKey(session);}
    public Map<UUID,S> originals(UUID session){return Map.copyOf(matches.getOrDefault(session,Map.of()));}
    public void recover(UUID session,UUID player,S snapshot,boolean pendingRestore){if(pendingRestore)pending.put(player,snapshot);else matches.computeIfAbsent(session,id->new LinkedHashMap<>()).put(player,snapshot);}
    public java.util.concurrent.CompletionStage<Void> applyAsync(UUID session,List<UUID> players,L loadout) {return applyAsync(session,players,loadout,()->true);}
    public java.util.concurrent.CompletionStage<Void> applyAsync(UUID session,List<UUID> players,L loadout,java.util.function.BooleanSupplier current) {
        if(durability==null){apply(session,players,loadout);return java.util.concurrent.CompletableFuture.completedFuture(null);}
        var snapshots=new LinkedHashMap<UUID,S>();
        for(UUID id:players){if(blocked(id) || barriers.contains(id) || matches.values().stream().anyMatch(m->m.containsKey(id)))throw new IllegalStateException("Player state is already isolated");snapshots.put(id,gateway.capture(id));}
        barriers.addAll(players);
        java.util.concurrent.CompletionStage<Void> commit;
        try { commit=durability.store(session,Map.copyOf(snapshots)); }
        catch(RuntimeException error){barriers.removeAll(players);return java.util.concurrent.CompletableFuture.failedFuture(error);}
        return commit.thenRun(()->{
            matches.put(session,snapshots);
            if(!current.getAsBoolean()){end(session);throw new java.util.concurrent.CancellationException("Preparation cancelled during durability barrier");}
            try{for(UUID id:players)gateway.apply(id,loadout);}catch(RuntimeException failure){end(session);throw failure;}
        }).whenComplete((ignored,error)->barriers.removeAll(players));
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
        snapshots.forEach((player,snapshot)-> { pending.put(player,snapshot); if(durability!=null)durability.pending(player); retry(player); });
    }
    /** Detach a single eliminated player's original exactly once; respawn/join owns the retry. */
    public void defer(UUID session,UUID player) {
        Map<UUID,S> snapshots=matches.get(session); if(snapshots==null) return;
        S snapshot=snapshots.remove(player); if(snapshot!=null){pending.putIfAbsent(player,snapshot);if(durability!=null)durability.pending(player);}
    }
    public boolean retry(UUID player) {
        S snapshot=pending.get(player); if (snapshot==null) return true;
        try { if (gateway.restore(player,snapshot)) { pending.remove(player); return true; } }
        catch (RuntimeException error) { errors.accept(player,error); }
        return false;
    }
    public boolean blocked(UUID player) { return pending.containsKey(player) || barriers.contains(player); }
    public int pendingCount() { return pending.size(); }
    public void close() { List.copyOf(matches.keySet()).forEach(this::end); }
}
