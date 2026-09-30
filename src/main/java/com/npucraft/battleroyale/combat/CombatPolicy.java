package com.npucraft.battleroyale.combat;
import com.npucraft.battleroyale.session.GameSession;
import java.util.UUID;
/** The identical rule for online victims and offline surrogates. Unknown sources are environmental. */
public final class CombatPolicy {
    private CombatPolicy() {}
    public static boolean blocks(GameSession session,long now,UUID attacker,UUID victim) {
        if(attacker==null || attacker.equals(victim) || !session.players().containsKey(attacker) || !session.players().containsKey(victim))return false;
        return session.protection().map(w->w.active(now)).orElse(false) || session.sameTeam(attacker,victim);
    }
}
