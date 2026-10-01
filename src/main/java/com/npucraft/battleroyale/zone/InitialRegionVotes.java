package com.npucraft.battleroyale.zone;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * One room/session/map ballot, mutated by its owning server thread.
 * The caller gates voting by phase and supplies the current eligible roster at tally/freeze time.
 * No player, room, map, Bukkit or persistent state is retained outside this ballot.
 */
public final class InitialRegionVotes {
    public record Result(InitialZoneCenters.Region region,int votes,int totalVotes) {}
    private final List<InitialZoneCenters.Region> regions;
    private final Map<String,InitialZoneCenters.Region> byId;
    private final Map<UUID,String> ballots=new HashMap<>();
    public InitialRegionVotes(InitialZoneCenters centers){
        regions=Objects.requireNonNull(centers).regions();
        if(regions.isEmpty())throw new IllegalArgumentException("Named initial regions required for voting");
        var values=new LinkedHashMap<String,InitialZoneCenters.Region>();regions.forEach(region->values.put(region.id(),region));byId=Collections.unmodifiableMap(values);
    }
    /** Replaces this player's previous vote. Repeating the same vote returns false and never adds a vote. */
    public boolean vote(UUID player,String regionId){
        Objects.requireNonNull(player);
        if(regionId==null||!byId.containsKey(regionId))throw new IllegalArgumentException("Unknown initial center region: "+regionId);
        return !regionId.equals(ballots.put(player,regionId));
    }
    public Optional<String> choice(UUID player){return Optional.ofNullable(ballots.get(Objects.requireNonNull(player)));}
    public boolean remove(UUID player){return ballots.remove(Objects.requireNonNull(player))!=null;}
    public void clear(){ballots.clear();}
    /** Retain only currently participating voters; reconnect/new-session policy remains with the room owner. */
    public void retain(Set<UUID> eligible){ballots.keySet().retainAll(Set.copyOf(eligible));}
    /** All regions are present in configured order, including those with zero eligible votes. */
    public Map<String,Integer> counts(Set<UUID> eligible){
        Objects.requireNonNull(eligible);var counts=new LinkedHashMap<String,Integer>();regions.forEach(region->counts.put(region.id(),0));
        for(UUID player:eligible){String id=ballots.get(player);if(id!=null)counts.compute(id,(unused,count)->count+1);}
        return Collections.unmodifiableMap(counts);
    }
    /** Uniform choice among the highest count; a zero-vote ballot is a uniform choice among all regions. */
    public Result choose(Set<UUID> eligible,RandomGenerator random){
        Objects.requireNonNull(random);var counts=counts(eligible);int most=counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        var tied=regions.stream().filter(region->counts.get(region.id())==most).toList();
        var chosen=tied.get(random.nextInt(tied.size()));
        return new Result(chosen,most,counts.values().stream().mapToInt(Integer::intValue).sum());
    }
}