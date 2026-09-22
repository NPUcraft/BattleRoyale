package com.lastsector.progression;
import java.util.*;
/** Immutable, Bukkit-free facts suitable for durable outbox replay. Wall times are epoch milliseconds. */
public record MatchResult(UUID sessionId, String roomId, String mapId, int teamSize, long startedAt,
                          long completedAt, int totalPlayers, int totalTeams, CompletionReason reason,
                          Map<UUID,Integer> placements, List<PlayerResult> players, PeriodKeys periods) {
    public enum CompletionReason { NORMAL, TIE, ADMIN_END, RECOVERY_ABANDONED, INTERNAL_ABORT }
    public record PlayerResult(UUID playerId, String name, UUID teamId, int placement, boolean winner,
                               boolean tieWinner, int kills, int deaths, int assists, double damage,
                               long survivalSeconds, int ratingBefore, int ratingDelta, int ratingAfter,
                               long killScoreDelta) {
        public PlayerResult {
            Objects.requireNonNull(playerId); Objects.requireNonNull(name); Objects.requireNonNull(teamId);
            if(name.length()>64 || placement<1 || kills<0 || deaths<0 || deaths>1 || assists<0 ||
                    !Double.isFinite(damage) || damage<0 || survivalSeconds<0 || killScoreDelta<0 || ratingBefore<0 || ratingAfter<0)
                throw new IllegalArgumentException("Invalid player result");
        }
    }
    public MatchResult {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(roomId); Objects.requireNonNull(mapId);
        Objects.requireNonNull(reason); Objects.requireNonNull(periods);
        placements=Map.copyOf(placements); players=List.copyOf(players);
        if (teamSize<1 || startedAt>completedAt || totalTeams<1 || totalPlayers<1 || totalPlayers!=players.size() || placements.size()!=totalTeams)
            throw new IllegalArgumentException("Invalid match result");
        if(!players.stream().map(PlayerResult::teamId).collect(java.util.stream.Collectors.toSet()).equals(placements.keySet()))throw new IllegalArgumentException("Result Team roster mismatch");
        var identities=new HashSet<UUID>();
        for(var p:players) if (!identities.add(p.playerId()) || !Objects.equals(placements.get(p.teamId()),p.placement()) ||
                p.placement()>totalTeams || (reason==CompletionReason.NORMAL || reason==CompletionReason.TIE) && p.winner()!=(p.placement()==1) || (p.tieWinner() && (!p.winner() || reason!=CompletionReason.TIE)))
            throw new IllegalArgumentException("Inconsistent result roster");
    }
    public boolean official() { return reason==CompletionReason.NORMAL || reason==CompletionReason.TIE; }
}
