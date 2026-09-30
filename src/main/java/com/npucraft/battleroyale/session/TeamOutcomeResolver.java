package com.npucraft.battleroyale.session;
import com.npucraft.battleroyale.team.GameTeam;
import java.util.*;
import java.util.stream.Collectors;
/** Solo is a one-member team. Only the final team's elimination tick can join a tie. */
public final class TeamOutcomeResolver implements MatchOutcomeResolver {
    @Override public Optional<MatchOutcome> resolve(GameSession session,Map<UUID,Long> deaths,long tick,long now) {
        if(session.state()!=GameState.RUNNING || session.outcome().isPresent())return Optional.empty();
        var active=session.teams().values().stream().filter(t->t.playerIds().stream().anyMatch(session::combatActive)).toList();
        if(active.size()>1)return Optional.empty();
        Set<GameTeam> winners=new HashSet<>(active);boolean tie=false;
        if(active.isEmpty()) {
            for(var team:session.teams().values())if(team.playerIds().stream().anyMatch(id->Objects.equals(deaths.get(id),tick)))winners.add(team);
            tie=winners.size()>1;if(!tie)winners.clear();
        }
        Set<UUID> teams=winners.stream().map(GameTeam::teamId).collect(Collectors.toSet());
        Set<UUID> players=winners.stream().flatMap(t->t.playerIds().stream()).collect(Collectors.toSet());
        return Optional.of(new MatchOutcome(players,tie,tie?"SAME_TICK_TEAMS":teams.isEmpty()?"NO_SURVIVORS":"LAST_ACTIVE_TEAM",now,tick,teams));
    }
}
