package com.lastsector.paper;
import com.lastsector.combat.*;
import com.lastsector.config.*;
import com.lastsector.player.PlayerState;
import com.lastsector.service.*;
import com.lastsector.session.*;
import com.lastsector.spawn.SpawnPlanner;
import com.lastsector.spawn.SpawnPreparation;
import com.lastsector.zone.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
/** Composition of start preparation and one owned, interval-gated loop per running session. */
public final class PaperMatches implements MatchLifecycle {
    private final JavaPlugin plugin;
    private final ConfigurationSnapshot configuration;
    private final GameScheduler scheduler;
    private final GameClock clock;
    private final RandomGenerator random;
    private final PlayerGateway players;
    private final com.lastsector.player.PlayerIsolation<com.lastsector.player.MatchPlayerSnapshot,com.lastsector.loadout.LoadoutDefinition> isolation;
    private final LoadoutEditor loadouts;
    private final WorldSanitizer sanitizer;
    private final MatchContent content;
    private final org.bukkit.NamespacedKey groundMarker;
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Set<Entry> draining=new HashSet<>();
    private boolean closed;
    public PaperMatches(JavaPlugin plugin,ConfigurationSnapshot configuration,GameScheduler scheduler,GameClock clock,
            RandomGenerator random,PlayerGateway players,LoadoutEditor loadouts,WorldSanitizer sanitizer,MatchContent content,
            com.lastsector.player.PlayerIsolation<com.lastsector.player.MatchPlayerSnapshot,com.lastsector.loadout.LoadoutDefinition> isolation,org.bukkit.NamespacedKey groundMarker) {
        this.plugin=plugin; this.configuration=configuration; this.scheduler=scheduler; this.clock=clock;
        this.random=random; this.players=players;
        this.loadouts=loadouts; this.sanitizer=sanitizer; this.content=content; this.isolation=isolation; this.groundMarker=groundMarker;
    }
    @Override public void start(GameSession session,Runnable ready,Consumer<Throwable> failed) {
        var starters=session.players().values().stream().filter(p -> p.state()!=PlayerState.DISCONNECTED)
                .map(com.lastsector.player.GamePlayer::playerId).filter(id -> {
                    Player p=plugin.getServer().getPlayer(id); return p!=null && p.isOnline();
                }).toList();
        if (starters.isEmpty()) throw new IllegalStateException("No online starters");
        var profile=configuration.zoneProfiles().stream().filter(p -> p.id().equals(session.room().zoneProfileId())).findFirst().orElseThrow();
        var initial=ZoneGeometry.initial(session.selectedMap().orElseThrow().playableArea(),profile.initialHalfSize(starters.size()),random);
        session.initialZone(initial);
        Entry entry=new Entry(session,profile,new PaperZoneUi(plugin.getServer(),configuration.settings().zoneUi()));
        entries.put(session.sessionId(),entry);
        var loadout=loadouts.definition(session.room().loadoutId()); // Immutable STARTING snapshot.
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));
        entry.worldId=world.getUID();
        sanitizer.register(world,session.sessionId(),failed);
        if(closed || entries.get(session.sessionId())!=entry || session.state()!=GameState.STARTING)
            throw new IllegalStateException("Match cancelled during initial sanitation");
        entry.loot=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new java.util.Random(random.nextLong()),groundMarker,scheduler);
        entry.preparation=new SpawnPreparation(new PaperSpawnTerrain(plugin,session,sanitizer,entry.loot::generate,
                ()->isolation.apply(session.sessionId(),starters,loadout)),new SpawnPlanner(initial,session.room().spawn(),starters.size(),random),
                starters,()-> session.state()==GameState.STARTING && entries.get(session.sessionId())==entry,scheduler,clock,ready,failed);
    }
    @Override public void running(GameSession session,Consumer<Throwable> failed) {
        Entry entry=Objects.requireNonNull(entries.get(session.sessionId()));
        long now=clock.nanoTime();
        session.runningZone(new ZoneRuntime(session.initialZone().orElseThrow(),entry.profile,random,now),
                new ProtectionWindow(now,session.room().pvpProtectionDuration()));
        entry.damagePulse=new DamagePulse(now);
        players.notify(session.players().keySet(),"protection-started",session.room().pvpProtectionDuration().getSeconds());
        entry.protectionExpired=session.room().pvpProtectionDuration().isZero();
        entry.task=new SessionLoop(scheduler,()->tick(entry),failed);
    }
    private void tick(Entry entry) {
        GameSession session=entry.session;
        if (entries.get(session.sessionId())!=entry || session.state()!=GameState.RUNNING) return;
        long now=clock.nanoTime(); var zone=session.zone().orElseThrow(); zone.update(now);
        boolean pulse=entry.damagePulse.due(now);
        if (!entry.protectionExpired && !session.protection().orElseThrow().active(now)) {
            entry.protectionExpired=true; entry.hazards.clear();
            players.notify(session.players().keySet(),"protection-ended");
        }
        entry.tick++;
        for (var gamePlayer:session.players().values()) {
            UUID id=gamePlayer.playerId(); Player player=plugin.getServer().getPlayer(id);
            if (gamePlayer.state()!=PlayerState.ALIVE || player==null || !player.isOnline() || player.isDead()
                    || !player.getWorld().getName().equals(session.gameWorld().orElseThrow().worldName())) {
                entry.ui.detach(id); continue;
            }
            if (player.getFireTicks()<=0) entry.hazards.burning(id,null);
            if (pulse) {
                var location=player.getLocation();
                double amount=ZoneDamage.amount(zone.current(),location.getX(),location.getZ(),zone.stage());
                if (amount>0) applyZoneDamage(player,new ZoneDamage.Context(session.sessionId(),zone.stageIndex(),
                        zone.current().distanceOutside(location.getX(),location.getZ()),amount));
            }
            if (!player.isDead()) entry.ui.render(player,zone,entry.tick);
        }
    }
    private void applyZoneDamage(Player player,ZoneDamage.Context context) {
        double max=Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue();
        player.setHealth(ZoneDamage.healthAfter(player.getHealth(),max,context.amount()));
    }
    public Optional<Entry> protectedPlayer(UUID id) {
        return entries.values().stream().filter(e -> e.session.state()==GameState.RUNNING
                && e.session.players().containsKey(id) && e.session.players().get(id).state()==PlayerState.ALIVE
                && e.session.protection().orElseThrow().active(clock.nanoTime())).findFirst();
    }
    public boolean blocks(Entry entry,UUID attacker,UUID victim) {
        return ProtectionPolicy.blocks(entry.session.protection().orElseThrow(),clock.nanoTime(),entry.session.players().keySet(),attacker,victim);
    }
    public Collection<Entry> protectedEntries() {
        return entries.values().stream().filter(e -> e.session.state()==GameState.RUNNING
                && e.session.protection().orElseThrow().active(clock.nanoTime())).toList();
    }
    @Override public void disconnected(UUID id) { entries.values().forEach(e -> e.ui.detach(id)); }
    @Override public void checkJoin(UUID player) {
        if(isolation.blocked(player)) throw new IllegalStateException("Your previous match state must be restored before joining");
    }
    @Override public void restore(GameSession session) { isolation.end(session.sessionId()); }
    public String loot(UUID session) { Entry entry=entries.get(session); return entry==null || entry.loot==null ? "loot=N/A" : entry.loot.diagnostics(); }
    @Override public void stop(GameSession session,Runnable drained) {
        Entry entry=entries.remove(session.sessionId());
        if(entry==null) { drained.run(); return; }
        entry.stopLoop();
        draining.add(entry);
        if(entry.loot!=null) entry.loot.stop();
        Runnable completed=()-> {
            java.util.concurrent.CompletableFuture<Void> loot=entry.loot==null?java.util.concurrent.CompletableFuture.completedFuture(null):entry.loot.stop();
            loot.thenRun(()-> { if(!closed) { sanitizer.remove(entry.worldId); draining.remove(entry); drained.run(); } });
        };
        if(entry.preparation!=null) entry.preparation.stop(completed); else completed.run();
    }
    @Override public void close() {
        closed=true;
        for(Entry entry:List.copyOf(entries.values())) {
            isolation.end(entry.session.sessionId());
            if(entry.loot!=null) entry.loot.close();
            if(entry.worldId!=null) sanitizer.remove(entry.worldId);
            entry.stopLoop(); if(entry.preparation!=null) entry.preparation.close();
        }
        entries.clear();
        for(Entry entry:List.copyOf(draining)) {
            if(entry.loot!=null) entry.loot.close();
            if(entry.worldId!=null) sanitizer.remove(entry.worldId);
            if(entry.preparation!=null) entry.preparation.close();
        }
        draining.clear();
    }
    public static final class Entry {
        public final GameSession session;
        public final PvPHazardTracker hazards=new PvPHazardTracker();
        final ZoneProfile profile;
        final PaperZoneUi ui;
        SpawnPreparation preparation;
        PaperLootRuntime loot;
        UUID worldId;
        SessionLoop task;
        DamagePulse damagePulse;
        long tick;
        boolean protectionExpired;
        Entry(GameSession session,ZoneProfile profile,PaperZoneUi ui) { this.session=session; this.profile=profile; this.ui=ui; }
        void stopLoop() { if(task!=null) task.close(); ui.close(); hazards.clear(); }
    }
}

