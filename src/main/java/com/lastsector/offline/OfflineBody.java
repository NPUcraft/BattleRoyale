package com.lastsector.offline;
import java.time.Duration;
import java.util.*;
/** Server-thread linearization point for lethal damage, deadlines and reconnect races. */
public final class OfflineBody {
    public enum State { RESERVED,LIVE,RESTORING,RECONNECTED,ELIMINATED,RETIRED }
    private final UUID player,session,team;private final String name;private final long began,duration;
    private State state=State.RESERVED;private BodySnapshot snapshot;private UUID representation;
    public OfflineBody(UUID player,UUID session,UUID team,String name,BodySnapshot snapshot,long now,Duration window) {
        this.player=Objects.requireNonNull(player);this.session=Objects.requireNonNull(session);this.team=Objects.requireNonNull(team);this.name=name;
        this.snapshot=Objects.requireNonNull(snapshot);began=now;duration=window.toNanos();if(duration<0)throw new IllegalArgumentException("Negative window");
    }
    public UUID player(){return player;}public UUID session(){return session;}public UUID team(){return team;}public String name(){return name;}
    public State state(){return state;}public BodySnapshot snapshot(){return snapshot;}public UUID representation(){return representation;}
    public long disconnectedAt(){return began;}public long reconnectDeadline(){return began+duration;}
    public boolean expired(long now){return now-began>=duration;}
    public long remainingNanos(long now){return Math.max(0,duration-(now-began));}
    public boolean active(){return state==State.RESERVED || state==State.LIVE;}
    public void snapshot(BodySnapshot value){if(!active() && state!=State.RESTORING)throw new IllegalStateException("Body already retired");snapshot=Objects.requireNonNull(value);}
    public void spawned(UUID id){if(state!=State.RESERVED)throw new IllegalStateException("Already spawned");representation=Objects.requireNonNull(id);state=State.LIVE;}
    public boolean beginReconnect(long now){if(state!=State.LIVE || expired(now))return false;state=State.RESTORING;return true;}
    public void restoreFailed(){if(state!=State.RESTORING)throw new IllegalStateException("Not restoring");state=State.LIVE;}
    public void restored(){if(state!=State.RESTORING)throw new IllegalStateException("Not restoring");state=State.RECONNECTED;}
    public boolean eliminate(){if(!active())return false;state=State.ELIMINATED;return true;}
    public void retire(){state=State.RETIRED;}
}
