package com.npucraft.lastsector.session;
import java.util.*;
public record MatchOutcome(Set<UUID> winnerIds,boolean tie,String reason,long completedAt,long tick,Set<UUID> winningTeamIds) {
    public MatchOutcome(Set<UUID> winnerIds,boolean tie,String reason,long completedAt,long tick) {this(winnerIds,tie,reason,completedAt,tick,Set.of());}
    public MatchOutcome { winnerIds=Set.copyOf(winnerIds);winningTeamIds=Set.copyOf(winningTeamIds); if(tie && winnerIds.size()<2) throw new IllegalArgumentException("Tie requires at least two winners"); }
}
