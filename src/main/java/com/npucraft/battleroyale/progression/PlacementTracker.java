package com.npucraft.battleroyale.progression;
import java.util.*;
/** Main-thread owned. Observe once at the end of each combat tick, after all eliminations. */
public final class PlacementTracker {
    public record Batch(long tick, Set<UUID> teams, int placement) {
        public Batch { teams = Set.copyOf(teams); if (teams.isEmpty() || placement < 1) throw new IllegalArgumentException("Invalid batch"); }
    }
    public record Snapshot(Set<UUID> unresolved, Map<UUID,Integer> assigned, List<Batch> batches) {
        public Snapshot { unresolved=Set.copyOf(unresolved); assigned=Map.copyOf(assigned); batches=List.copyOf(batches); }
    }
    private final Set<UUID> unresolved = new LinkedHashSet<>();
    private final Map<UUID,Integer> assigned = new LinkedHashMap<>();
    private final List<Batch> batches = new ArrayList<>();
    public PlacementTracker(Set<UUID> teams) { if (teams.isEmpty()) throw new IllegalArgumentException("Empty roster"); unresolved.addAll(teams); }
    public PlacementTracker(Snapshot saved) {
        unresolved.addAll(saved.unresolved()); assigned.putAll(saved.assigned()); batches.addAll(saved.batches());
        if(assigned.values().stream().anyMatch(p->p<1||p>assigned.size()+unresolved.size()))throw new IllegalArgumentException("Invalid recovered placement");
        var seen=new HashSet<UUID>();
        for(var batch:batches) for(var team:batch.teams()) {
            if (!seen.add(team) || !Objects.equals(assigned.get(team),batch.placement())) throw new IllegalArgumentException("Inconsistent placement history");
        }
        if (!seen.equals(assigned.keySet()) || unresolved.stream().anyMatch(assigned::containsKey)) throw new IllegalArgumentException("Inconsistent placement snapshot");
    }
    public void observe(Set<UUID> active, long tick) {
        if (!unresolved.containsAll(active)) throw new IllegalArgumentException("Eliminated/unknown team became active");
        var eliminated=new LinkedHashSet<>(unresolved); eliminated.removeAll(active);
        if (!eliminated.isEmpty()) assign(eliminated, active.size()+1, tick);
        if (unresolved.size()==1) assign(Set.copyOf(unresolved),1,tick);
    }
    private void assign(Set<UUID> teams,int placement,long tick) {
        batches.add(new Batch(tick,teams,placement));
        teams.forEach(team -> assigned.put(team,placement)); unresolved.removeAll(teams);
    }
    public Snapshot snapshot() { return new Snapshot(unresolved,assigned,batches); }
}
