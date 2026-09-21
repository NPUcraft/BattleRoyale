package com.lastsector.session;
import java.util.*;
public record MatchOutcome(Set<UUID> winnerIds,boolean tie,String reason,long completedAt,long tick) {
    public MatchOutcome { winnerIds=Set.copyOf(winnerIds); if(tie && winnerIds.size()<2) throw new IllegalArgumentException("Tie requires at least two winners"); }
}
