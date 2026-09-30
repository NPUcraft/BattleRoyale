package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.combat.*;
import com.npucraft.battleroyale.config.CombatSettings;
import com.npucraft.battleroyale.death.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.GameClock;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import com.npucraft.battleroyale.service.UiText;
import java.util.*;
import java.util.function.*;

/** Per-session combat aggregate; listener translation and room resource lifecycle remain separate. */
public final class PaperCombatSession implements AutoCloseable {
    private final JavaPlugin plugin;
    private final GameSession session;
    private final GameClock clock;
    private final CombatTracker tracker;
    private final EliminationService eliminations;
    private final PaperDeathBoxes boxes;
    private final MatchOutcomeResolver outcomes=new TeamOutcomeResolver();
    private final Map<UUID,String> names=new HashMap<>();
    private final Set<UUID> zoneDamage=new HashSet<>();
    private final Consumer<Throwable> failed;
    private java.util.function.Consumer<com.npucraft.battleroyale.death.DeathBox> cosmetic=box->{};
    public void cosmetics(java.util.function.Consumer<com.npucraft.battleroyale.death.DeathBox> value){cosmetic=value;}
    private com.npucraft.battleroyale.progression.SessionProgress progress;
    public void progress(com.npucraft.battleroyale.progression.SessionProgress value){progress=value;tracker.damageObserver(record->{if(!session.sameTeam(record.attacker(),record.victim()))value.damage(record.attacker(),record.amount());});eliminations.restoreTicks(value.snapshot().eliminationTicks());recoveredBatch=value.snapshot().eliminationTicks().values().stream().max(Long::compare).orElse(null);}
    private Long recoveredBatch;
    private long began;
    private long dirtyTick=Long.MIN_VALUE;
    private boolean initialOutcomeCheck=true;
    public PaperCombatSession(JavaPlugin plugin,GameSession session,CombatSettings settings,GameClock clock,
            NativeItemSerializer serializer,StoredExperienceBottles bottles,Consumer<UUID> eliminated,Consumer<Throwable> failed) {
        this.plugin=plugin;this.session=session;this.clock=clock;this.failed=failed;began=clock.nanoTime();
        session.players().keySet().forEach(id->{var p=plugin.getServer().getPlayer(id);String name=p==null?plugin.getServer().getOfflinePlayer(id).getName():p.getName();names.put(id,name==null?id.toString():name);});
        tracker=new CombatTracker(session.players().keySet(),settings,clock);
        boxes=new PaperDeathBoxes(plugin,session,settings.boxReach(),serializer,bottles);
        eliminations=new EliminationService(session,tracker,clock.nanoTime(),box->{
            if(progress!=null)progress.eliminated(box.deceased(),box.elapsedNanos(),box.eliminationTick());
            dirtyTick=box.eliminationTick(); eliminated.accept(box.deceased());
            boxes.create(box,this::name);cosmetic.accept(box);
            for(UUID id:session.players().keySet()) {var player=plugin.getServer().getPlayer(id);if(player!=null) player.sendMessage(UiText.value(box.deceasedName()).append(UiText.muted(" — ")).append(DeathReasonRenderer.render(player,box.reason(),this::name)));}
        });
    }
    public long elapsedNanos(){return Math.max(0,clock.nanoTime()-began);}
    public void recoveredElapsed(long elapsed){began=clock.nanoTime()-elapsed;eliminations.recoveredElapsed(elapsed,clock.nanoTime());}
    public CombatTracker tracker() { return tracker; }
    public long now() { return clock.nanoTime(); }
    public PaperDeathBoxes boxes() { return boxes; }
    public void recoveredName(UUID id,String name){if(!session.players().containsKey(id))throw new IllegalArgumentException("Unknown participant");names.put(id,Objects.requireNonNull(name));}
    public String name(UUID id) { return names.getOrDefault(id,id.toString()); }
    public void eliminate(EliminationRequest request) { eliminations.eliminate(request); }
    public void fail(Throwable error) { failed.accept(error); }
    public void zone(UUID victim,Runnable damage) { zoneDamage.add(victim);try {damage.run();} finally {zoneDamage.remove(victim);} }
    public boolean isZone(UUID victim) { return zoneDamage.contains(victim); }
    public Optional<MatchOutcome> endTick(long tick) {
        if(dirtyTick>tick) return Optional.empty();
        if(dirtyTick==Long.MIN_VALUE && !initialOutcomeCheck)return Optional.empty();
        initialOutcomeCheck=false;
        long batch=dirtyTick==Long.MIN_VALUE?(recoveredBatch==null?tick:recoveredBatch):dirtyTick;dirtyTick=Long.MIN_VALUE;recoveredBatch=null;
        if(progress!=null)progress.observe(session,batch);
        // The recorded batch identity, not callback wall time, defines ties.
        return outcomes.resolve(session,eliminations.eliminationTicks(),batch,clock.nanoTime());
    }
    public void ending() { boxes.closeViewers(); tracker.clear(); }
    @Override public void close() { boxes.close(); eliminations.clear();zoneDamage.clear();names.clear(); }
}
