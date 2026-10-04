package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.combat.*;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.player.PlayerState;
import com.npucraft.battleroyale.service.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.spawn.SpawnPlanner;
import com.npucraft.battleroyale.spawn.SpawnPreparation;
import com.npucraft.battleroyale.zone.*;
import org.bukkit.GameRules;
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
    private final com.npucraft.battleroyale.player.PlayerIsolation<com.npucraft.battleroyale.player.MatchPlayerSnapshot,com.npucraft.battleroyale.loadout.LoadoutDefinition> isolation;
    private final LoadoutEditor loadouts;
    private final WorldSanitizer sanitizer;
    private final MatchContent content;
    private final org.bukkit.NamespacedKey groundMarker;
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Set<Entry> draining=new HashSet<>();
    private volatile boolean closed;
    private final java.util.concurrent.ExecutorService airdropIo=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"BattleRoyale-airdrop-io");thread.setDaemon(true);return thread;});
    private PaperProgression progression;
    private PaperCosmeticEffects cosmeticEffects;
    public void progression(PaperProgression value){progression=value;cosmeticEffects=new PaperCosmeticEffects(plugin,value.config());}
    private void cosmeticHooks(Entry entry){if(cosmeticEffects!=null){entry.combat.boxes().skin(id->cosmeticEffects.skin(entry,id));entry.combat.cosmetics(box->cosmeticEffects.kill(entry,box));}}
    private java.util.function.BooleanSupplier recoveryReady=()->true,storageHealthy=()->true;
    private java.util.function.Predicate<UUID> durableBlocked=id->false;
    public void recoveryAccess(java.util.function.BooleanSupplier ready,java.util.function.BooleanSupplier healthy,java.util.function.Predicate<UUID> blocked){recoveryReady=ready;storageHealthy=healthy;durableBlocked=blocked;}
    private java.util.function.Predicate<java.util.UUID> administrationBlocked=id->false;
    public void administrationBlocked(java.util.function.Predicate<java.util.UUID> value){administrationBlocked=value;}
    @Override public void checkStart(){if(!recoveryReady.getAsBoolean())throw new IllegalStateException("BattleRoyale 正在恢复比赛，请稍候。");if(!storageHealthy.getAsBoolean())throw new IllegalStateException("恢复存储暂不可用，无法开始比赛。");}

    private Consumer<GameSession> finished=session->{};
    private Consumer<Collection<UUID>> beforePrepare=players->{};
    public void beforePrepare(Consumer<Collection<UUID>> action){beforePrepare=Objects.requireNonNull(action);}
    private final NativeItemSerializer itemSerializer;
    private final StoredExperienceBottles bottles;
    private final CelebrationEffects celebrations;
    private final PaperSpectators spectators;private final MessageService messages;private final PaperBodySnapshots bodySnapshots;
    @Override public void onFinished(Consumer<GameSession> finished) {this.finished=finished;}
    public PaperMatches(JavaPlugin plugin,ConfigurationSnapshot configuration,GameScheduler scheduler,GameClock clock,
            RandomGenerator random,PlayerGateway players,LoadoutEditor loadouts,WorldSanitizer sanitizer,MatchContent content,
            com.npucraft.battleroyale.player.PlayerIsolation<com.npucraft.battleroyale.player.MatchPlayerSnapshot,com.npucraft.battleroyale.loadout.LoadoutDefinition> isolation,org.bukkit.NamespacedKey groundMarker,
            NativeItemSerializer itemSerializer,StoredExperienceBottles bottles,CelebrationEffects celebrations,PaperSpectators spectators,MessageService messages) {
        this.plugin=plugin; this.configuration=configuration; this.scheduler=scheduler; this.clock=clock;
        this.random=random; this.players=players;
        this.loadouts=loadouts; this.sanitizer=sanitizer; this.content=content; this.isolation=isolation; this.groundMarker=groundMarker;
        this.itemSerializer=itemSerializer;this.bottles=bottles;this.celebrations=celebrations;this.spectators=spectators;this.messages=messages;bodySnapshots=new PaperBodySnapshots(itemSerializer);
    }
    @Override public java.util.concurrent.CompletionStage<Void> prepareDurably(GameSession session) {
        beforePrepare.accept(List.copyOf(session.players().keySet()));
        var profile=configuration.zoneProfiles().stream().filter(p->p.id().equals(session.room().zoneProfileId())).findFirst().orElseThrow();
        // Freeze the same random initial square before filesystem preparation, so a large template
        // can be copied by selected regions. All later shrinking and spawn planning use this square.
        session.initialZone(profile.initialZone(session.selectedMap().orElseThrow(),session.players().size(),random,session.initialRegionId().orElse(null)));
        Entry entry=new Entry(session,profile,new PaperZoneUi(plugin.getServer(),configuration.settings().zoneUi()));entries.put(session.sessionId(),entry);
        entry.border=new PaperZoneBorder(plugin.getServer(),configuration.settings().zoneUi());
        entry.preparationUi=new PaperPreparationUi(plugin.getServer(),scheduler,clock,session.players().keySet(),()->preparationStatus(entry));
        if(progression!=null)entry.progress=new com.npucraft.battleroyale.progression.SessionProgress(session.teams().keySet(),progression.freeze(session.players().keySet()));
        entry.offline=new PaperOfflineBodies(plugin,entry,configuration.settings().disconnect(),clock,bodySnapshots,messages,id->{isolation.defer(session.sessionId(),id);isolation.retry(id);});
        return isolation.applyAsync(session.sessionId(),List.copyOf(session.players().keySet()),loadouts.definition(session.room().loadoutId()),()->session.state()==GameState.PREPARING && entries.get(session.sessionId())==entry).thenRun(()->{entry.snapshotsPrepared=true;players.notify(session.players().keySet(),"teams-assigned",session.teams().size());});
    }
    @Override public void start(GameSession session,Runnable ready,Consumer<Throwable> failed) {
        var starters=List.copyOf(session.players().keySet());
        Entry entry=Objects.requireNonNull(entries.get(session.sessionId()));
        var initial=session.initialZone().orElseThrow();
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));entry.worldId=world.getUID();
        // API explosions (fire-charge blasts, flint-ignited placed TNT) run through the
        // mob-griefing-gated interaction and would not break a single block if the cloned
        // template inherited mobGriefing=false. Match worlds carry no griefing mobs, so the
        // rule is switched on for terrain damage without any downside.
        world.setGameRule(GameRules.MOB_GRIEFING,true);
        sanitizer.register(world,session.sessionId(),failed);
        if(closed || entries.get(session.sessionId())!=entry || session.state()!=GameState.STARTING)
            throw new IllegalStateException("Match cancelled during initial sanitation");
        entry.loot=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new java.util.Random(random.nextLong()),groundMarker,scheduler,airdropIo);
        var fallbackLandings=new LinkedHashMap<UUID,org.bukkit.Location>();
        entry.preparation=new SpawnPreparation(new PaperSpawnTerrain(plugin,session,sanitizer,entry.loot::generate,
                ()->{},(id,location)->{fallbackLandings.put(id,location.clone());entry.offline.planned(id,location);}),new SpawnPlanner(initial,session.room().spawn(),starters.size(),random),
                starters,()-> session.state()==GameState.STARTING && entries.get(session.sessionId())==entry,scheduler,clock,()->{
                    if(closed||entries.get(session.sessionId())!=entry||session.state()!=GameState.STARTING)return;
                    entry.flight=new PaperFlightDeployment(plugin,session,fallbackLandings,new java.util.Random(random.nextLong()));
                    // The zone tick only runs for RUNNING; deployment still shows the initial boundary
                    // so players can judge the square while skydiving, and the border actually blocks.
                    var initialZone=session.initialZone().orElseThrow();
                    entry.flight.boundary((player,t)->{
                        entry.ui.renderWall(player,initialZone,t);
                        if(t%20==0)entry.border.apply(player,initialZone);
                    });
                    entry.flight.start(ready,failed);
                },failed);
    }
    private PaperPreparationUi.Status preparationStatus(Entry entry){
        if(!entry.snapshotsPrepared)return PaperPreparationUi.Status.phase(PaperPreparationUi.Phase.PLAYER_DATA);
        if(entry.flight!=null){
            var flight=entry.flight.progress();String phase=flight.phase().toString();
            String zh=switch(phase){case "LOADING"->"加载随机航线";case "BOARDING"->"登机中";case "FLYING"->"走出平台即可跳伞";case "LANDING"->"等待队员着陆";default->"跳伞准备";};
            String en=switch(phase){case "LOADING"->"Loading random route";case "BOARDING"->"Boarding";case "FLYING"->"Step off to deploy";case "LANDING"->"Waiting for landings";default->"Deployment";};
            return new PaperPreparationUi.Status(PaperPreparationUi.Phase.FLIGHT,flight.completed(),flight.total(),zh,en);
        }
        if(entry.preparation==null)return PaperPreparationUi.Status.phase(PaperPreparationUi.Phase.MAP);
        if(entry.preparation.preparingLoot())return new PaperPreparationUi.Status(PaperPreparationUi.Phase.LOOT,entry.loot.progressCompleted(),entry.loot.progressTotal());
        return new PaperPreparationUi.Status(PaperPreparationUi.Phase.SPAWNS,entry.preparation.preparedCount(),entry.preparation.starterCount());
    }
    @Override public void running(GameSession session,Consumer<Throwable> failed) {
        Entry entry=Objects.requireNonNull(entries.get(session.sessionId()));
        if(entry.preparationUi!=null)entry.preparationUi.close();
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
        entry.airdrops=new PaperAirdrops(plugin,session,sanitizer,content,airdropIo,false);
        entry.horses=new PaperMatchHorses(plugin,session,sanitizer,content.horses(),false);
        entry.task=new SessionLoop(scheduler,()->tick(entry),failed);
    }
    private void tick(Entry entry) {long started=System.nanoTime();try{tickMeasured(entry);}finally{com.npucraft.battleroyale.admin.PerformanceMetricsService.LIVE.record(com.npucraft.battleroyale.admin.PerformanceMetricsService.Timer.ZONE_TICK,System.nanoTime()-started);}}
    private void tickMeasured(Entry entry) {
        if(!recoveryReady.getAsBoolean())return;
        GameSession session=entry.session;
        if (entries.get(session.sessionId())!=entry || session.state()!=GameState.RUNNING) return;
        long now=clock.nanoTime(); var zone=session.zone().orElseThrow(); zone.update(now);
        announceZonePhaseChange(entry,zone);
        if(entry.airdrops!=null)entry.airdrops.tick(zone,now);
        if(entry.horses!=null)entry.horses.tick(zone,now);
        // Supplies first, then the staged ground refill: it needs this tick's already-updated zone.
        if(entry.loot!=null){entry.loot.tickSupplies();entry.loot.tickZone(zone);}
        boolean pulse=entry.damagePulse.due(now);
        if(pulse)entry.feedbackPulses++;
        if (!entry.protectionExpired && !session.protection().orElseThrow().active(now)) {
            entry.protectionExpired=true;
            players.notify(session.players().keySet(),"protection-ended");
        }
        entry.tick++;
        entry.offline.tick(entry.tick,pulse);
        var expected=new HashSet<UUID>();
        // The border follows the interpolated square on the same interval as the rest of the zone UI.
        int borderInterval=configuration.settings().zoneUi().bossbarInterval();
        for (var gamePlayer:session.players().values()) {
            UUID id=gamePlayer.playerId(); Player player=plugin.getServer().getPlayer(id);
            if (gamePlayer.state()!=PlayerState.ALIVE || player==null || !player.isOnline() || player.isDead()
                    || !player.getWorld().getName().equals(session.gameWorld().orElseThrow().worldName())) {
                // No per-tick detach: the retain sweep below removes only viewers that stopped rendering, so a
                // spectator transition never fights the per-interval render.
                entry.border.restore(id);
                if(player!=null)ZoneDamageFeedback.clear(player);
                continue;
            }
            expected.add(id);
            if (player.getFireTicks()<=0) entry.hazards.burning(id,null);
            if (pulse) {
                var location=player.getLocation();
                double amount=ZoneDamage.amount(zone.current(),location.getX(),location.getZ(),zone.stage());
                if (amount>0) entry.combat.zone(id,()->applyZoneDamage(entry,player,new ZoneDamage.Context(session.sessionId(),zone.stageIndex(),
                        zone.current().distanceOutside(location.getX(),location.getZ()),amount)));
            }
            var at=player.getLocation();var drop=entry.airdrops==null?null:entry.airdrops.navigationTarget(at.getX(),at.getZ()).orElse(null);
            entry.ui.render(player,zone,entry.tick,session.activeCount(),gamePlayer.kills(),session.activeTeamCount(),false,drop);
            if(entry.tick%borderInterval==0)entry.border.apply(player,zone);
        }
        for(var presence:spectators.registry().session(session.sessionId())){
            var viewer=plugin.getServer().getPlayer(presence.player());if(viewer==null)continue;
            expected.add(viewer.getUniqueId());
            entry.ui.render(viewer,zone,entry.tick,session.activeCount(),0,session.activeTeamCount(),true);
        }
        entry.ui.retain(expected);
    }
    /** Global shrink-start cue: a phase/stage/target key flip fires a world-wide sound and title.
     *  The key also covers the final continuation (phase stays SHRINKING while the next square
     *  collapses to zero), so every shrink onset gets exactly one announcement. */
    private void announceZonePhaseChange(Entry entry,ZoneRuntime zone) {
        long nextHalf=zone.next()==null?-1:Math.round(zone.next().halfSize());
        String key=zone.phase()+":"+zone.stageIndex()+":"+nextHalf;
        String previous=entry.lastZoneKey;entry.lastZoneKey=key;
        if(previous==null||key.equals(previous)||zone.phase()!=ZonePhase.SHRINKING)return;
        boolean finale=nextHalf==0;
        var audience=new HashSet<UUID>();
        for(var gamePlayer:entry.session.players().values())audience.add(gamePlayer.playerId());
        for(var presence:spectators.registry().session(entry.session.sessionId()))audience.add(presence.player());
        for(UUID id:audience) {
            Player player=plugin.getServer().getPlayer(id);
            if(player==null||!player.isOnline()||!player.getWorld().getUID().equals(entry.worldId))continue;
            var locale=I18n.locale(player);
            player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text(
                        I18n.text(locale,finale?"最终安全区开始收拢":"第 "+zone.stageNumber()+" 阶段：安全区开始缩小",
                                finale?"Final safe zone collapsing":"Stage "+zone.stageNumber()+": the safe zone is shrinking"),UiText.WARNING)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD,true)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false),
                net.kyori.adventure.text.Component.text(I18n.text(locale,"赶紧向安全区移动","Move to the safe zone"),UiText.VALUE)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false)));
            player.playSound(player.getLocation(),org.bukkit.Sound.ENTITY_WITHER_SPAWN,0.7f,1f);
        }
    }
    private void applyZoneDamage(Entry entry,Player player,ZoneDamage.Context context) {
        double max=Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue();
        player.setHealth(ZoneDamage.healthAfter(player.getHealth(),max,context.amount()));
        ZoneDamageFeedback.apply(player,entry.feedbackPulses);
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
    public void recoveryCompleted(){if(clock instanceof com.npucraft.battleroyale.zone.RecoveryClock recoveryClock)recoveryClock.resume();for(var entry:List.copyOf(entries.values()))if(entry.recoveryStart!=null){var start=entry.recoveryStart;entry.recoveryStart=null;start.run();}}
    public Collection<Entry> allEntries(){return List.copyOf(entries.values());}
    public void discardRecovery(GameSession session){var entry=entries.remove(session.sessionId());if(entry!=null){entry.stopLoop();if(entry.offline!=null)entry.offline.close();if(entry.loot!=null)entry.loot.close();if(entry.airdrops!=null)entry.airdrops.close();entry.closeCombat();sanitizer.remove(entry.worldId);}}
    /** Recovery retains the saved square; random initial selection belongs exclusively to new preparation. */
    static void restoreZone(GameSession session,com.npucraft.battleroyale.recovery.SessionRecoverySnapshot saved,ZoneProfile profile,RandomGenerator random,long now){
        session.recoveredZone(ZoneRuntime.restore(saved.zone(),profile,random,now),new ProtectionWindow(now,java.time.Duration.ofNanos(saved.protectionRemainingNanos())));
    }
    public void recover(GameSession session,com.npucraft.battleroyale.recovery.SessionRecoverySnapshot saved,Consumer<Throwable> failed) {
        var profile=configuration.zoneProfiles().stream().filter(p->p.id().equals(session.room().zoneProfileId())).findFirst().orElseThrow();
        Entry entry=new Entry(session,profile,new PaperZoneUi(plugin.getServer(),configuration.settings().zoneUi()));entries.put(session.sessionId(),entry);
        entry.border=new PaperZoneBorder(plugin.getServer(),configuration.settings().zoneUi());
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));entry.worldId=world.getUID();entry.recoveredLootComplete=true;
        sanitizer.recover(world,session.sessionId(),saved.sanitizedBlocks(),saved.sanitizedEntities(),failed);
        long now=clock.nanoTime();restoreZone(session,saved,profile,random,now);
        entry.loot=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new java.util.Random(random.nextLong()),groundMarker,scheduler,airdropIo);
        if(session.state()==GameState.RUNNING){entry.loot.recoverAutomatic();entry.airdrops=new PaperAirdrops(plugin,session,sanitizer,content,airdropIo,true);entry.horses=new PaperMatchHorses(plugin,session,sanitizer,content.horses(),true);}
        entry.protectionExpired=saved.protectionRemainingNanos()==0;entry.damagePulse=new DamagePulse(now);
        entry.offline=new PaperOfflineBodies(plugin,entry,configuration.settings().disconnect(),clock,bodySnapshots,messages,id->{isolation.defer(session.sessionId(),id);isolation.retry(id);});
        entry.combat=new PaperCombatSession(plugin,session,configuration.settings().combat(),clock,itemSerializer,bottles,id->{entry.changed();if(entry.offline.find(id)!=null)isolation.defer(session.sessionId(),id);else spectators.eliminated(session,id);entry.ui.detach(id);entry.hazards.burning(id,null);},failed);
        if(saved.progression()!=null){entry.progress=new com.npucraft.battleroyale.progression.SessionProgress(saved.progression());entry.combat.progress(entry.progress);if(saved.progression().result()!=null && progression!=null)entry.resultDurable=progression.submit(saved.progression().result());}
        cosmeticHooks(entry);
        saved.participants().forEach(p->entry.combat.recoveredName(p.id(),p.name()));
        if(saved.outcome()!=null)for(UUID winner:saved.outcome().players())messages.offlineWinner(winner,saved.outcome().tie());
        entry.combat.recoveredElapsed(saved.elapsedNanos());entry.combat.tracker().restore(saved.hits());entry.combat.boxes().recover(saved.boxes(),entry.combat::name);
        if(session.state()==GameState.RUNNING){
            for(var p:saved.participants())if(p.state().equals("ALIVE") || p.state().equals("DISCONNECTED")){
                var state=Objects.requireNonNull(p.current(),"Missing recovered combatant state");var at=state.position();state=state.at(new com.npucraft.battleroyale.offline.BodyPosition(world.getUID(),at.x(),at.y(),at.z(),at.yaw(),at.pitch()));
                entry.offline.recover(p.id(),p.name(),state,p.state().equals("ALIVE")?configuration.settings().disconnect().reconnectWindow().toNanos():p.reconnectRemainingNanos());
            }
            entry.recoveryStart=entry.offline::started;
            entry.task=new SessionLoop(scheduler,()->tick(entry),failed);
        }else{
            entry.combat.ending();entry.recoveryStart=()->{entry.showcase=new WinnerShowcase(scheduler,clock,java.time.Duration.ofNanos(saved.showcaseRemainingNanos()),()->celebrations.title(session,entry.combat::name),()->{if(cosmeticEffects==null)celebrations.fire(session);else cosmeticEffects.win(entry,celebrations);},seconds->celebrations.returnCountdown(session,seconds),()->finished.accept(session),failed);};
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
                if(entry.progress!=null && progression!=null){
                    var result=entry.progress.finish(entry.session,entry.combat.elapsedNanos(),progression.config().ranking(),entry.combat::name);
                    entry.resultDurable=progression.submit(result);
                    PaperEconomyRewards.settle(progression,result);
                }
                for(UUID winner:outcome.winnerIds())if(plugin.getServer().getPlayer(winner)==null)messages.offlineWinner(winner,outcome.tie());
                entry.stopLoop();entry.combat.ending();entry.offline.ending();
                entry.showcase=new WinnerShowcase(scheduler,clock,configuration.settings().combat().showcaseDuration(),
                        ()->celebrations.title(entry.session,entry.combat::name),()->{if(cosmeticEffects==null)celebrations.fire(entry.session);else cosmeticEffects.win(entry,celebrations);},
                        seconds->celebrations.returnCountdown(entry.session,seconds),()->finished.accept(entry.session),entry.combat::fail);
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
        if(entry.preparationUi!=null)entry.preparationUi.detach(id);
        var fallback=entry.flight==null?null:entry.flight.disconnect(player);
        entry.offline.disconnect(player);
        if(fallback!=null)entry.offline.planned(id,fallback);
    }
    public java.util.Map<String,Long> resourceCounts(){var all=new java.util.HashSet<Entry>(entries.values());all.addAll(draining);return java.util.Map.of("entries",(long)entries.size(),"draining",(long)draining.size(),"cleanupFailed",all.stream().filter(e->e.cleanupFailed).count(),"bossbars",all.stream().mapToLong(e->e.ui.size()+(e.preparationUi==null?0:e.preparationUi.size())).sum(),"deathboxes",all.stream().mapToLong(e->e.combat==null?0:e.combat.boxes().size()).sum(),"offlineBodies",all.stream().mapToLong(e->e.offline==null?0:e.offline.size()).sum());}
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
        if(administrationBlocked.test(player))throw new IllegalStateException("请先关闭地图编辑器。");
        if(progression!=null)progression.check(player);
        if(!recoveryReady.getAsBoolean())throw new IllegalStateException("BattleRoyale 正在恢复比赛，请稍候。");
        if(durableBlocked.test(player))throw new IllegalStateException("请等待玩家原始状态恢复完成后再加入。");
        if(spectators.registry().find(player).isPresent())throw new IllegalStateException("请先退出观战。");
        if(isolation.blocked(player)) throw new IllegalStateException("请等待上一局的玩家状态恢复完成后再加入。");
    }
    @Override public void restore(GameSession session) { spectators.cleanup(session);var entry=entries.get(session.sessionId());if(entry!=null){if(entry.border!=null)entry.border.restoreAll();if(entry.preparationUi!=null)entry.preparationUi.close();if(entry.flight!=null)entry.flight.stop();if(entry.offline!=null)entry.offline.close();}isolation.end(session.sessionId()); }
    @Override public void leaveEnding(GameSession session,UUID player){
        Entry entry=entries.get(session.sessionId());
        if(entry==null||entry.session!=session||session.state()!=GameState.ENDING||session.outcome().isEmpty()||!session.players().containsKey(player))throw new IllegalStateException("只能在比赛结束后的展示阶段提前返回大厅。");
        if(!recoveryReady.getAsBoolean()||!storageHealthy.getAsBoolean())throw new IllegalStateException("恢复或存储暂未就绪，请稍后再返回大厅。");
        EndingReturnPolicy.require(session,player,entry.resultDurable);
        // World-border restore point: an early return must never carry the match edge into the lobby.
        if(entry.border!=null)entry.border.restore(player);
        Player leaving=plugin.getServer().getPlayer(player);if(leaving!=null)ZoneDamageFeedback.clear(leaving);
        if(!spectators.leave(player,false)){entry.ui.detach(player);isolation.defer(session.sessionId(),player);isolation.retry(player);}
        celebrations.detach(player);
    }
    public String loot(UUID session) { Entry entry=entries.get(session); return entry==null?"loot=N/A":(entry.loot==null?"loot=N/A":entry.loot.diagnostics())+(entry.recoveredLootComplete?" (recovered; no point/ground regeneration)":"")+(entry.airdrops==null?"":" "+entry.airdrops.diagnostics())+(entry.horses==null?"":" "+entry.horses.diagnostics()); }
    @Override public void abortReason(GameSession session,boolean admin){var entry=entries.get(session.sessionId());if(entry!=null)entry.abortReason=admin?com.npucraft.battleroyale.progression.MatchResult.CompletionReason.ADMIN_END:com.npucraft.battleroyale.progression.MatchResult.CompletionReason.INTERNAL_ABORT;}
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
            var airdrops=entry.airdrops==null?java.util.concurrent.CompletableFuture.<Void>completedFuture(null):entry.airdrops.stop();
            var horses=entry.horses==null?java.util.concurrent.CompletableFuture.<Void>completedFuture(null):entry.horses.stop();
            var flight=entry.flight==null?java.util.concurrent.CompletableFuture.<Void>completedFuture(null):entry.flight.stop();
            java.util.concurrent.CompletableFuture.allOf(loot,airdrops,horses,flight).whenComplete((unused,error)-> {
                if(closed)return;
                Runnable finish=()->{
                    if(closed)return;
                    if(error!=null){
                        // A failed equipment/platform cleanup cannot safely authorize world deletion.
                        // Keep the owned world and expose the failure instead of silently abandoning the callback.
                        entry.cleanupFailed=true;
                        players.error("Match cleanup failed; room and world retained for administrator recovery: "+session.sessionId(),error);
                        return;
                    }
                    sanitizer.remove(entry.worldId);draining.remove(entry);drained.run();
                };
                if(plugin.getServer().isPrimaryThread())finish.run();else plugin.getServer().getScheduler().runTask(plugin,finish);
            });
        };
        if(entry.preparation!=null) entry.preparation.stop(completed); else completed.run();
    }
    @Override public void close() {
        closed=true;
        for(Entry entry:List.copyOf(entries.values())) {
            abortedResult(entry);
            spectators.cleanup(entry.session);if(entry.offline!=null)entry.offline.close();
            entry.closeCombat();celebrations.close(entry.session.sessionId());if(cosmeticEffects!=null)cosmeticEffects.close(entry.session.sessionId());
            if(entry.preparationUi!=null)entry.preparationUi.close();if(entry.flight!=null)entry.flight.close();
            isolation.end(entry.session.sessionId());
            if(entry.loot!=null) entry.loot.close();
            if(entry.worldId!=null) sanitizer.remove(entry.worldId);
            entry.stopLoop(); if(entry.preparation!=null) entry.preparation.close();
        }
        entries.clear();
        for(Entry entry:List.copyOf(draining)) {
            if(entry.preparationUi!=null)entry.preparationUi.close();if(entry.flight!=null)entry.flight.close();
            if(entry.loot!=null) entry.loot.close();
            if(entry.horses!=null) entry.horses.close();
            if(entry.worldId!=null) sanitizer.remove(entry.worldId);
            if(entry.preparation!=null) entry.preparation.close();
        }
        draining.clear();
        airdropIo.shutdown();
        // Ledger writes never call Bukkit. Drain only this IO before RoomRuntimeService deletes worlds.
        try{
            if(!airdropIo.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)){
                airdropIo.shutdownNow();
                if(!airdropIo.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("空投记录写入未结束，保留比赛世界供恢复。");
            }
        }catch(InterruptedException error){airdropIo.shutdownNow();Thread.currentThread().interrupt();throw new IllegalStateException("等待空投记录写入时被中断，保留比赛世界。",error);}
    }
    public static final class Entry {
        public final GameSession session;
        public com.npucraft.battleroyale.progression.SessionProgress progress;
        com.npucraft.battleroyale.progression.MatchResult.CompletionReason abortReason=com.npucraft.battleroyale.progression.MatchResult.CompletionReason.INTERNAL_ABORT;
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
        PaperZoneBorder border;
        SpawnPreparation preparation;
        PaperPreparationUi preparationUi;
        PaperFlightDeployment flight;
        boolean snapshotsPrepared;
        boolean cleanupFailed;
        PaperLootRuntime loot;
        PaperAirdrops airdrops;
        /** Read-only handle for listeners outside this package (signal gun). */
        public PaperAirdrops airdropsView(){return airdrops;}
        PaperMatchHorses horses;
        UUID worldId;
        SessionLoop task;
        DamagePulse damagePulse;
        long tick;
        /** Phase/stage/target snapshot of the previous zone tick; null until the first tick. */
        String lastZoneKey;
        long feedbackPulses;
        boolean protectionExpired;
        boolean recoveredLootComplete;
        Entry(GameSession session,ZoneProfile profile,PaperZoneUi ui) { this.session=session; this.profile=profile; this.ui=ui; }
        void stopLoop() { if(preparationUi!=null)preparationUi.close();if(flight!=null)flight.stop();if(task!=null) task.close();if(airdrops!=null)airdrops.close();if(horses!=null)horses.close();if(border!=null)border.close();ui.close(); hazards.clear(); }
        void closeCombat() {if(showcase!=null)showcase.close();if(combat!=null)combat.close();}
    }
}

