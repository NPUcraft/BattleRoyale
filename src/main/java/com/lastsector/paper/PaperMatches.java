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
    private PaperProgression progression;
    private PaperCosmeticEffects cosmeticEffects;
    public void progression(PaperProgression value){progression=value;cosmeticEffects=new PaperCosmeticEffects(plugin,value.config());}
    private void cosmeticHooks(Entry entry){if(cosmeticEffects!=null){entry.combat.boxes().skin(id->cosmeticEffects.skin(entry,id));entry.combat.cosmetics(box->cosmeticEffects.kill(entry,box));}}
    private java.util.function.BooleanSupplier recoveryReady=()->true,storageHealthy=()->true;
    private java.util.function.Predicate<UUID> durableBlocked=id->false;
    public void recoveryAccess(java.util.function.BooleanSupplier ready,java.util.function.BooleanSupplier healthy,java.util.function.Predicate<UUID> blocked){recoveryReady=ready;storageHealthy=healthy;durableBlocked=blocked;}
    private java.util.function.Predicate<java.util.UUID> administrationBlocked=id->false;
    public void administrationBlocked(java.util.function.Predicate<java.util.UUID> value){administrationBlocked=value;}
    @Override public void checkStart(){if(!recoveryReady.getAsBoolean())throw new IllegalStateException("LastSector is still recovering sessions");if(!storageHealthy.getAsBoolean())throw new IllegalStateException("Match cannot start because recovery storage is unavailable");}

    private Consumer<GameSession> finished=session->{};
    private final NativeItemSerializer itemSerializer;
    private final StoredExperienceBottles bottles;
    private final CelebrationEffects celebrations;
    private final PaperSpectators spectators;private final MessageService messages;private final PaperBodySnapshots bodySnapshots;
    @Override public void onFinished(Consumer<GameSession> finished) {this.finished=finished;}
    public PaperMatches(JavaPlugin plugin,ConfigurationSnapshot configuration,GameScheduler scheduler,GameClock clock,
            RandomGenerator random,PlayerGateway players,LoadoutEditor loadouts,WorldSanitizer sanitizer,MatchContent content,
            com.lastsector.player.PlayerIsolation<com.lastsector.player.MatchPlayerSnapshot,com.lastsector.loadout.LoadoutDefinition> isolation,org.bukkit.NamespacedKey groundMarker,
            NativeItemSerializer itemSerializer,StoredExperienceBottles bottles,CelebrationEffects celebrations,PaperSpectators spectators,MessageService messages) {
        this.plugin=plugin; this.configuration=configuration; this.scheduler=scheduler; this.clock=clock;
        this.random=random; this.players=players;
        this.loadouts=loadouts; this.sanitizer=sanitizer; this.content=content; this.isolation=isolation; this.groundMarker=groundMarker;
        this.itemSerializer=itemSerializer;this.bottles=bottles;this.celebrations=celebrations;this.spectators=spectators;this.messages=messages;bodySnapshots=new PaperBodySnapshots(itemSerializer);
    }
    @Override public java.util.concurrent.CompletionStage<Void> prepareDurably(GameSession session) {
        var profile=configuration.zoneProfiles().stream().filter(p->p.id().equals(session.room().zoneProfileId())).findFirst().orElseThrow();
        Entry entry=new Entry(session,profile,new PaperZoneUi(plugin.getServer(),configuration.settings().zoneUi()));entries.put(session.sessionId(),entry);
        if(progression!=null)entry.progress=new com.lastsector.progression.SessionProgress(session.teams().keySet(),progression.freeze(session.players().keySet()));
        entry.offline=new PaperOfflineBodies(plugin,entry,configuration.settings().disconnect(),clock,bodySnapshots,messages,id->{isolation.defer(session.sessionId(),id);isolation.retry(id);});
        return isolation.applyAsync(session.sessionId(),List.copyOf(session.players().keySet()),loadouts.definition(session.room().loadoutId()),()->session.state()==GameState.PREPARING && entries.get(session.sessionId())==entry).thenRun(()->players.notify(session.players().keySet(),"teams-assigned",session.teams().size()));
    }
    @Override public void start(GameSession session,Runnable ready,Consumer<Throwable> failed) {
        var starters=List.copyOf(session.players().keySet());
        Entry entry=Objects.requireNonNull(entries.get(session.sessionId()));
        var initial=ZoneGeometry.initial(session.selectedMap().orElseThrow().playableArea(),entry.profile.initialHalfSize(starters.size()),random);
        session.initialZone(initial);
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));entry.worldId=world.getUID();
        sanitizer.register(world,session.sessionId(),failed);
        if(closed || entries.get(session.sessionId())!=entry || session.state()!=GameState.STARTING)
            throw new IllegalStateException("Match cancelled during initial sanitation");
        entry.loot=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new java.util.Random(random.nextLong()),groundMarker,scheduler);
        entry.preparation=new SpawnPreparation(new PaperSpawnTerrain(plugin,session,sanitizer,entry.loot::generate,
                ()->{},(id,location)->entry.offline.planned(id,location)),new SpawnPlanner(initial,session.room().spawn(),starters.size(),random),
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
        entry.combat=new PaperCombatSession(plugin,session,configuration.settings().combat(),clock,itemSerializer,bottles,id->{
            entry.changed();if(entry.offline.find(id)!=null)isolation.defer(session.sessionId(),id);else spectators.eliminated(session,id);entry.ui.detach(id);entry.hazards.burning(id,null);
        },failed);
        cosmeticHooks(entry);
        if(entry.progress!=null){entry.progress.started();entry.combat.progress(entry.progress);}
        entry.offline.started();entry.changed();
        entry.task=new SessionLoop(scheduler,()->tick(entry),failed);
    }
    private void tick(Entry entry) {long started=System.nanoTime();try{tickMeasured(entry);}finally{com.lastsector.admin.PerformanceMetricsService.LIVE.record(com.lastsector.admin.PerformanceMetricsService.Timer.ZONE_TICK,System.nanoTime()-started);}}
    private void tickMeasured(Entry entry) {
        if(!recoveryReady.getAsBoolean())return;
        GameSession session=entry.session;
        if (entries.get(session.sessionId())!=entry || session.state()!=GameState.RUNNING) return;
        long now=clock.nanoTime(); var zone=session.zone().orElseThrow(); zone.update(now);
        boolean pulse=entry.damagePulse.due(now);
        if (!entry.protectionExpired && !session.protection().orElseThrow().active(now)) {
            entry.protectionExpired=true;
            players.notify(session.players().keySet(),"protection-ended");
        }
        entry.tick++;
        entry.offline.tick(entry.tick,pulse);
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
                if (amount>0) entry.combat.zone(id,()->applyZoneDamage(player,new ZoneDamage.Context(session.sessionId(),zone.stageIndex(),
                        zone.current().distanceOutside(location.getX(),location.getZ()),amount)));
            }
            if (!player.isDead()) entry.ui.render(player,zone,entry.tick,session.activeCount(),gamePlayer.kills(),session.activeTeamCount(),false);
        }
        for(var presence:spectators.registry().session(session.sessionId())){var viewer=plugin.getServer().getPlayer(presence.player());if(viewer!=null)entry.ui.render(viewer,zone,entry.tick,session.activeCount(),0,session.activeTeamCount(),true);}
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
    public Optional<Entry> activePlayer(UUID id) {
        return entries.values().stream().filter(e->e.session.state()==GameState.RUNNING && e.session.players().containsKey(id)
                && e.session.players().get(id).state()==PlayerState.ALIVE).findFirst();
    }
    public long now(){return clock.nanoTime();}
    public void recoveryCompleted(){if(clock instanceof com.lastsector.zone.RecoveryClock recoveryClock)recoveryClock.resume();for(var entry:List.copyOf(entries.values()))if(entry.recoveryStart!=null){var start=entry.recoveryStart;entry.recoveryStart=null;start.run();}}
    public Collection<Entry> allEntries(){return List.copyOf(entries.values());}
    public void discardRecovery(GameSession session){var entry=entries.remove(session.sessionId());if(entry!=null){entry.stopLoop();if(entry.offline!=null)entry.offline.close();entry.closeCombat();sanitizer.remove(entry.worldId);}}
    public void recover(GameSession session,com.lastsector.recovery.SessionRecoverySnapshot saved,Consumer<Throwable> failed) {
        var profile=configuration.zoneProfiles().stream().filter(p->p.id().equals(session.room().zoneProfileId())).findFirst().orElseThrow();
        Entry entry=new Entry(session,profile,new PaperZoneUi(plugin.getServer(),configuration.settings().zoneUi()));entries.put(session.sessionId(),entry);
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));entry.worldId=world.getUID();entry.recoveredLootComplete=true;
        sanitizer.recover(world,session.sessionId(),saved.sanitizedBlocks(),saved.sanitizedEntities(),failed);
        long now=clock.nanoTime();session.recoveredZone(ZoneRuntime.restore(saved.zone(),profile,random,now),new ProtectionWindow(now,java.time.Duration.ofNanos(saved.protectionRemainingNanos())));
        entry.protectionExpired=saved.protectionRemainingNanos()==0;entry.damagePulse=new DamagePulse(now);
        entry.offline=new PaperOfflineBodies(plugin,entry,configuration.settings().disconnect(),clock,bodySnapshots,messages,id->{isolation.defer(session.sessionId(),id);isolation.retry(id);});
        entry.combat=new PaperCombatSession(plugin,session,configuration.settings().combat(),clock,itemSerializer,bottles,id->{entry.changed();if(entry.offline.find(id)!=null)isolation.defer(session.sessionId(),id);else spectators.eliminated(session,id);entry.ui.detach(id);entry.hazards.burning(id,null);},failed);
        if(saved.progression()!=null){entry.progress=new com.lastsector.progression.SessionProgress(saved.progression());entry.combat.progress(entry.progress);if(saved.progression().result()!=null && progression!=null)entry.resultDurable=progression.submit(saved.progression().result());}
        cosmeticHooks(entry);
        saved.participants().forEach(p->entry.combat.recoveredName(p.id(),p.name()));
        if(saved.outcome()!=null)for(UUID winner:saved.outcome().players())messages.offlineWinner(winner,saved.outcome().tie());
        entry.combat.recoveredElapsed(saved.elapsedNanos());entry.combat.tracker().restore(saved.hits());entry.combat.boxes().recover(saved.boxes(),entry.combat::name);
        if(session.state()==GameState.RUNNING){
            for(var p:saved.participants())if(p.state().equals("ALIVE") || p.state().equals("DISCONNECTED")){
                var state=Objects.requireNonNull(p.current(),"Missing recovered combatant state");var at=state.position();state=state.at(new com.lastsector.offline.BodyPosition(world.getUID(),at.x(),at.y(),at.z(),at.yaw(),at.pitch()));
                entry.offline.recover(p.id(),p.name(),state,p.state().equals("ALIVE")?configuration.settings().disconnect().reconnectWindow().toNanos():p.reconnectRemainingNanos());
            }
            entry.recoveryStart=entry.offline::started;
            entry.task=new SessionLoop(scheduler,()->tick(entry),failed);
        }else{
            entry.combat.ending();entry.recoveryStart=()->{entry.showcase=new WinnerShowcase(scheduler,clock,java.time.Duration.ofNanos(saved.showcaseRemainingNanos()),()->celebrations.title(session,entry.combat::name),()->{if(cosmeticEffects==null)celebrations.fire(session);else cosmeticEffects.win(entry,celebrations);},()->finished.accept(session),failed);};
        }
    }
    public Collection<Entry> runningEntries() { return entries.values().stream().filter(e->e.session.state()==GameState.RUNNING).toList(); }
    public Collection<Entry> combatEntries() { return entries.values().stream().filter(e->e.combat!=null).toList(); }
    public boolean endingPlayer(UUID id,UUID world) {
        return entries.values().stream().anyMatch(e->e.session.state()==GameState.ENDING && e.inWorld(world)
                && e.session.players().containsKey(id) && e.session.players().get(id).state()==PlayerState.ALIVE);
    }
    public void endTick(long tick) {
        if(closed || !recoveryReady.getAsBoolean()) return;
        for(Entry entry:List.copyOf(entries.values())) if(entry.combat!=null && entry.session.state()==GameState.RUNNING) {
            try {entry.combat.endTick(tick).ifPresent(outcome->{
                entry.session.outcome(outcome);entry.changed();
                if(entry.progress!=null && progression!=null)entry.resultDurable=progression.submit(entry.progress.finish(entry.session,entry.combat.elapsedNanos(),progression.config().ranking(),entry.combat::name));
                for(UUID winner:outcome.winnerIds())if(plugin.getServer().getPlayer(winner)==null)messages.offlineWinner(winner,outcome.tie());
                entry.stopLoop();entry.combat.ending();entry.offline.ending();
                entry.showcase=new WinnerShowcase(scheduler,clock,configuration.settings().combat().showcaseDuration(),
                        ()->celebrations.title(entry.session,entry.combat::name),()->{if(cosmeticEffects==null)celebrations.fire(entry.session);else cosmeticEffects.win(entry,celebrations);},
                        ()->finished.accept(entry.session),entry.combat::fail);
            });} catch(RuntimeException error) {entry.combat.fail(error);}
        }
    }
    public String deathboxes(UUID session) {Entry entry=entries.get(session);return entry==null || entry.combat==null?"deathboxes=0":entry.combat.boxes().diagnostics();}
    public boolean blocks(Entry entry,UUID attacker,UUID victim) {
        return CombatPolicy.blocks(entry.session,clock.nanoTime(),attacker,victim);
    }
    public Collection<Entry> protectedEntries() {
        return entries.values().stream().filter(e -> e.session.state()==GameState.RUNNING
                && e.session.protection().orElseThrow().active(clock.nanoTime())).toList();
    }
    @Override public void disconnected(UUID id) {
        if(spectators.leave(id,true))return;
        Entry entry=participant(id);if(entry==null)return;entry.ui.detach(id);
        Player player=plugin.getServer().getPlayer(id);if(player==null)return;
        if(entry.session.state()==GameState.ENDING){isolation.defer(entry.session.sessionId(),id);return;}
        if(!isolation.ready(entry.session.sessionId()))throw new IllegalStateException("Disconnected before durable preparation completed");
        entry.offline.disconnect(player);
    }
    public java.util.Map<String,Long> resourceCounts(){var all=new java.util.HashSet<Entry>(entries.values());all.addAll(draining);return java.util.Map.of("entries",(long)entries.size(),"draining",(long)draining.size(),"bossbars",all.stream().mapToLong(e->e.ui.size()).sum(),"deathboxes",all.stream().mapToLong(e->e.combat==null?0:e.combat.boxes().size()).sum(),"offlineBodies",all.stream().mapToLong(e->e.offline==null?0:e.offline.size()).sum());}
    public Entry entry(UUID session){return entries.get(session);}
    public Entry participant(UUID id){return entries.values().stream().filter(e->e.session.players().containsKey(id)).findFirst().orElse(null);}
    public boolean joined(Player player){Entry entry=participant(player.getUniqueId());return entry!=null && entry.offline!=null && entry.offline.reconnect(player);}
    public org.bukkit.entity.Entity combatEntity(Entry entry,UUID id){
        if(!entry.session.combatActive(id))return null;var body=entry.offline.carrier(id);return body!=null?body:plugin.getServer().getPlayer(id);
    }
    public UUID combatIdentity(Entry entry,org.bukkit.entity.Entity entity){
        if(entity instanceof Player && entry.inWorld(entity.getWorld().getUID()) && entry.session.combatActive(entity.getUniqueId()))return entity.getUniqueId();
        var body=entry.offline==null?null:entry.offline.entity(entity);return body!=null && body.active() && entity.getUniqueId().equals(body.representation())?body.player():null;
    }
    public record Victim(Entry entry,UUID player) {}
    public Victim victim(org.bukkit.entity.Entity entity){
        for(Entry entry:entries.values())if(entry.session.state()==GameState.RUNNING && entry.inWorld(entity.getWorld().getUID())){UUID id=combatIdentity(entry,entity);if(id!=null)return new Victim(entry,id);}return null;
    }
    public boolean frozen(UUID player){if(durableBlocked.test(player) || isolation.blocked(player))return true;var entry=participant(player);return entry!=null && (entry.session.state()==GameState.PREPARING || entry.session.state()==GameState.STARTING || entry.session.players().get(player).state()==PlayerState.DISCONNECTED && entry.offline.find(player)!=null);}
    public String offline(UUID session){var entry=entries.get(session);return entry==null?"offline=none":entry.offline.diagnostics();}
    @Override public void checkJoin(UUID player) {
        if(administrationBlocked.test(player))throw new IllegalStateException("Close your map editor first");
        if(progression!=null)progression.check(player);
        if(!recoveryReady.getAsBoolean())throw new IllegalStateException("LastSector is still recovering sessions");
        if(durableBlocked.test(player))throw new IllegalStateException("Your durable player restore must finish before joining");
        if(spectators.registry().find(player).isPresent())throw new IllegalStateException("Leave spectating first");
        if(isolation.blocked(player)) throw new IllegalStateException("Your previous match state must be restored before joining");
    }
    @Override public void restore(GameSession session) { spectators.cleanup(session);var entry=entries.get(session.sessionId());if(entry!=null && entry.offline!=null)entry.offline.close();isolation.end(session.sessionId()); }
    public String loot(UUID session) { Entry entry=entries.get(session); return entry==null ? "loot=N/A" : entry.recoveredLootComplete?"loot=COMPLETE (recovered; no regeneration)":entry.loot==null?"loot=N/A":entry.loot.diagnostics(); }
    @Override public void abortReason(GameSession session,boolean admin){var entry=entries.get(session.sessionId());if(entry!=null)entry.abortReason=admin?com.lastsector.progression.MatchResult.CompletionReason.ADMIN_END:com.lastsector.progression.MatchResult.CompletionReason.INTERNAL_ABORT;}
    private void abortedResult(Entry entry){if(entry.progress!=null && entry.combat!=null && progression!=null && entry.resultDurable==null)entry.resultDurable=progression.submit(entry.progress.abort(entry.session,entry.combat.elapsedNanos(),progression.config().ranking(),entry.combat::name,entry.abortReason));}
    @Override public void stop(GameSession session,Runnable drained) {
        Entry waiting=entries.get(session.sessionId());
        if(waiting!=null)abortedResult(waiting);
        if(waiting!=null && waiting.resultDurable!=null && !waiting.resultDurable.isDone()){waiting.resultDurable.thenRun(()->stop(session,drained));return;}
        Entry entry=entries.remove(session.sessionId());
        if(entry==null) { drained.run(); return; }
        entry.stopLoop();
        entry.closeCombat();celebrations.close(session.sessionId());if(cosmeticEffects!=null)cosmeticEffects.close(session.sessionId());
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
            abortedResult(entry);
            spectators.cleanup(entry.session);if(entry.offline!=null)entry.offline.close();
            entry.closeCombat();celebrations.close(entry.session.sessionId());if(cosmeticEffects!=null)cosmeticEffects.close(entry.session.sessionId());
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
        public com.lastsector.progression.SessionProgress progress;
        com.lastsector.progression.MatchResult.CompletionReason abortReason=com.lastsector.progression.MatchResult.CompletionReason.INTERNAL_ABORT;
        public java.util.concurrent.CompletableFuture<Void> resultDurable;
        public boolean priorityCheckpoint=true;
        public void changed(){priorityCheckpoint=true;}
        public final PvPHazardTracker hazards=new PvPHazardTracker();
        public PaperCombatSession combat;
        public PaperOfflineBodies offline;
        WinnerShowcase showcase;
        Runnable recoveryStart;
        public boolean inWorld(UUID world) {return world.equals(worldId);}
        final ZoneProfile profile;
        final PaperZoneUi ui;
        SpawnPreparation preparation;
        PaperLootRuntime loot;
        UUID worldId;
        SessionLoop task;
        DamagePulse damagePulse;
        long tick;
        boolean protectionExpired;
        boolean recoveredLootComplete;
        Entry(GameSession session,ZoneProfile profile,PaperZoneUi ui) { this.session=session; this.profile=profile; this.ui=ui; }
        void stopLoop() { if(task!=null) task.close(); ui.close(); hazards.clear(); }
        void closeCombat() {if(showcase!=null)showcase.close();if(combat!=null)combat.close();}
    }
}

