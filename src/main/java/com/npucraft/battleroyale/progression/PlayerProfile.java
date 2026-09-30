package com.npucraft.battleroyale.progression;
import java.util.*;
public record PlayerProfile(UUID id, String name, long firstSeen, long lastSeen, int rating, int highestRating,
                            long killScore, long matches, long wins, long kills, long deaths, long assists,
                            double damage, long playSeconds, long top3, int bestPlacement,
                            Set<String> unlocks, Map<String,String> equipped) {
    public PlayerProfile { unlocks=Set.copyOf(unlocks); equipped=Map.copyOf(equipped); }
    public double winRate() { return matches==0?0:100.0*wins/matches; }
    public double killDeathRatio() { return deaths==0?kills:(double)kills/deaths; }
}
