package com.npucraft.lastsector.death;
import com.npucraft.lastsector.session.*;
import com.npucraft.lastsector.player.PlayerState;
import java.util.UUID;
public final class DeathBoxAccess {
    private DeathBoxAccess() {}
    public static boolean allowed(GameSession session,DeathBox box,UUID player,DeathPosition current,double reach) {
        var participant=session.players().get(player);
        return session.sessionId().equals(box.sessionId()) && session.state()==GameState.RUNNING && participant!=null
                && participant.state()==PlayerState.ALIVE && box.location().distanceSquared(current)<=reach*reach;
    }
}
