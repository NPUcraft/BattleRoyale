package com.npucraft.lastsector.session;
import java.util.*;
public interface MatchOutcomeResolver {
    Optional<MatchOutcome> resolve(GameSession session,Map<UUID,Long> eliminationTicks,long tick,long now);
}
