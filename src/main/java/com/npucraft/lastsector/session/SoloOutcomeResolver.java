package com.npucraft.lastsector.session;
import com.npucraft.lastsector.player.PlayerState;
import java.util.*;
import java.util.stream.Collectors;
/** Called only at a real tick boundary after that tick's elimination batch. */
public final class SoloOutcomeResolver implements MatchOutcomeResolver {
    @Override public Optional<MatchOutcome> resolve(GameSession session,Map<UUID,Long> deaths,long tick,long now) {
        if(session.room().teamSize()!=1 || session.state()!=GameState.RUNNING || session.outcome().isPresent()) return Optional.empty();
        // M6 owns disconnected contenders. Never turn a disconnect into a free M5 victory.
        if(session.players().values().stream().anyMatch(p->p.state()==PlayerState.DISCONNECTED)) return Optional.empty();
        Set<UUID> alive=session.players().values().stream().filter(p->p.state()==PlayerState.ALIVE).map(p->p.playerId()).collect(Collectors.toSet());
        if(alive.size()>1) return Optional.empty();
        if(alive.size()==1) return Optional.of(new MatchOutcome(alive,false,"LAST_ALIVE",now,tick));
        Set<UUID> batch=deaths.entrySet().stream().filter(e->e.getValue()==tick).map(Map.Entry::getKey).collect(Collectors.toSet());
        return Optional.of(batch.size()>=2?new MatchOutcome(batch,true,"SAME_TICK",now,tick):new MatchOutcome(Set.of(),false,"NO_SURVIVORS",now,tick));
    }
}
