package com.npucraft.lastsector.death;
import com.npucraft.lastsector.combat.*;
import com.npucraft.lastsector.session.*;
import com.npucraft.lastsector.player.PlayerState;
import java.util.*;
import java.util.function.Consumer;
/** Single commit point shared by current deaths and future offline-body/time-out adapters. */
public final class EliminationService {
    private final GameSession session;
    private final CombatTracker combat;
    private long started;
    private final Consumer<DeathBox> committed;
    private final Map<UUID,DeathBox> boxes=new LinkedHashMap<>();
    private final Map<UUID,Long> ticks=new HashMap<>();
    public EliminationService(GameSession session,CombatTracker combat,long started,Consumer<DeathBox> committed) {
        this.session=session; this.combat=combat; this.started=started; this.committed=committed;
    }
    public void recoveredElapsed(long elapsed,long now){if(!boxes.isEmpty())throw new IllegalStateException("Already started");started=now-elapsed;}
    public Optional<DeathBox> eliminate(EliminationRequest request) {
        var victim=session.players().get(request.victim());
        if(session.state()!=GameState.RUNNING || victim==null || !session.combatActive(request.victim())) return Optional.empty();
        var reason=combat.resolve(request.victim(),request.directAttacker(),request.cause(),id->session.players().containsKey(id));
        DeathBox box=new DeathBox(UUID.randomUUID(),session.sessionId(),request.victim(),request.name(),request.location(),
                Math.max(0,request.nanoTime()-started),request.tick(),reason,request.contents(),ExperienceMath.stored(request.totalExperience()));
        if(!session.eliminate(request.victim())) return Optional.empty();
        boxes.put(box.id(),box); ticks.put(request.victim(),request.tick());
        reason.killer().ifPresent(id->session.credit(id,true)); reason.assists().forEach(id->session.credit(id,false)); combat.forget(request.victim());
        // A failing visual callback cannot roll back the commit or create a second logical payload.
        committed.accept(box); return Optional.of(box);
    }
    public void restoreTicks(Map<UUID,Long> saved){if(!ticks.isEmpty())throw new IllegalStateException("Eliminations already initialized");ticks.putAll(saved);}
    public Map<UUID,Long> eliminationTicks() { return Map.copyOf(ticks); }
    public Collection<DeathBox> boxes() { return List.copyOf(boxes.values()); }
    public void clear() { boxes.clear(); ticks.clear(); combat.clear(); }
}
