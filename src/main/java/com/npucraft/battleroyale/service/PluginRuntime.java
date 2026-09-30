package com.npucraft.battleroyale.service;

import com.npucraft.battleroyale.config.ConfigurationSnapshot;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.Executors;

/** Paper composition root. Creates replacements before publishing reloads; owns shutdown. */
public final class PluginRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final FoundationService foundation;
    private final MessageService messages;
    private RoomRuntimeService rooms;
    private PaperMatches matches;
    private PaperProgression progression;
    private com.npucraft.battleroyale.config.MatchContent matchContent;
    public com.npucraft.battleroyale.config.MatchContent content(){return matchContent;}
    private final PaperMobLoot mobLoot;
    private final PaperLobby lobby;
    private final PaperDiagnostics diagnostics;
    public PaperDiagnostics diagnostics(){return diagnostics;}
    private com.npucraft.battleroyale.paper.PaperMapAdministration mapAdministration;
    public com.npucraft.battleroyale.paper.PaperMapAdministration mapAdministration(){return mapAdministration;}
    public boolean editing(java.util.UUID id){return mapAdministration!=null && mapAdministration.editing(id);}
    public java.util.concurrent.CompletionStage<Void> isolateEditor(java.util.UUID token,java.util.UUID player,java.util.function.BooleanSupplier current){return isolation.applyAsync(token,java.util.List.of(player),new com.npucraft.battleroyale.loadout.LoadoutDefinition("editor",java.util.Map.of(),0),current);}
    public void restoreEditor(java.util.UUID token){isolation.end(token);}
    public PaperLobby lobby(){return lobby;}
    private org.bukkit.scheduler.BukkitTask progressionTask;
    private boolean startupReported;
    public PaperProgression progression(){return progression;}
    private OnDemandWorldProvider provider;
    private final NativeItemSerializer itemSerializer=new NativeItemSerializer();
    private final PaperDamageProvenance provenance=new PaperDamageProvenance();
    private final StoredExperienceBottles bottles;
    private final CelebrationEffects celebrations;
    private final PaperSpectators spectators;
    public PaperSpectators spectators(){return spectators;}
    public void joined(org.bukkit.entity.Player player){if(!recoveryReady()){lobby.deferJoin(player);return;}var previous=matches.participant(player.getUniqueId());
        // A playerdata acknowledgement is authoritative even if a crash preceded SQL APPLIED/delete.
        // Never replace that acknowledged Lobby inventory with an older active-session checkpoint.
        if(previous!=null && previous.session.state()==com.npucraft.battleroyale.session.GameState.RUNNING && durablePlayers.wasRestored(player)){
            plugin.getLogger().warning("Recovered session conflicts with an acknowledged Lobby restore; ending "+previous.session.sessionId());rooms.debugEnd(previous.session.room().id());
        }
        if(isolation.blocked(player.getUniqueId()))isolation.retry(player.getUniqueId());else if(!durablePlayers.blocked(player.getUniqueId()) && !matches.joined(player))isolation.retry(player.getUniqueId());if(!isolation.blocked(player.getUniqueId()))messages.deliverOfflineResult(player);}
    public CelebrationEffects celebrations() {return celebrations;}
    public void deferRestore(java.util.UUID session,java.util.UUID player) {isolation.defer(session,player);}
    public org.bukkit.Location lobbySpawn() {return java.util.Objects.requireNonNull(plugin.getServer().getWorld(foundation.state().configuration().settings().lobbyWorld())).getSpawnLocation();}
    public PaperDamageProvenance provenance() { return provenance; }
    private final LoadoutEditor loadouts;
    private final WorldSanitizer sanitizer;
    private final org.bukkit.NamespacedKey groundMarker;
    private final PaperPlayerIsolation playerStates;
    private final PaperDurablePlayers durablePlayers;
    private final RecoveryEntityCleaner recoveryEntities=new RecoveryEntityCleaner();
    private com.npucraft.battleroyale.storage.RecoveryStorage storage;
    private PaperRecoveryCoordinator recovery;
    private com.npucraft.battleroyale.storage.StorageSettings storageSettings;
    public boolean recoveryReady(){return recovery!=null && recovery.ready();}
    public String storageDiagnostics(){return storage==null?"storage=initializing":storage.diagnostics();}
    public String recoveryDiagnostics(){return recovery==null?"bootstrap=INITIALIZING":recovery.diagnostics();}
    public String recoverySession(java.util.UUID id){return recovery==null?"recovery=INITIALIZING":recovery.sessionDiagnostics(id);}
    public void beginRecovery(){recovery.start();}
    private final com.npucraft.battleroyale.player.PlayerIsolation<com.npucraft.battleroyale.player.MatchPlayerSnapshot,com.npucraft.battleroyale.loadout.LoadoutDefinition> isolation;
    public LoadoutEditor loadouts() { return loadouts; }
    public int pendingRestoreCount(){return isolation.pendingCount();}
    public boolean pendingRestore(java.util.UUID player) { return isolation.blocked(player)||durablePlayers.blocked(player); }
    public void restorePlayer(java.util.UUID player) { isolation.retry(player); }
    public PaperMatches matches() { return matches; }
    public PluginRuntime(JavaPlugin plugin, FoundationService foundation, MessageService messages) {
        this.plugin = plugin; this.foundation = foundation; this.messages = messages;
        diagnostics=new PaperDiagnostics(plugin,this,foundation);
        lobby=new PaperLobby(plugin,this);plugin.getServer().getPluginManager().registerEvents(lobby,plugin);
        bottles=new StoredExperienceBottles(plugin);celebrations=new CelebrationEffects(plugin);
        groundMarker=new org.bukkit.NamespacedKey(plugin,"ground_loot_session");
        sanitizer=new WorldSanitizer(groundMarker);
        loadouts=new LoadoutEditor(plugin,itemSerializer);
        playerStates=new PaperPlayerIsolation(plugin,itemSerializer);
        durablePlayers=new PaperDurablePlayers(plugin,playerStates);
        isolation=new com.npucraft.battleroyale.player.PlayerIsolation<>(durablePlayers,(id,error)->messages.runtimeError("Player restoration pending: " + id,error));
        isolation.durability(durablePlayers);
        plugin.getServer().getPluginManager().registerEvents(recoveryEntities,plugin);
        spectators=new PaperSpectators(plugin,isolation,()->matches,messages);
        plugin.getServer().getPluginManager().registerEvents(loadouts,plugin);
        plugin.getServer().getPluginManager().registerEvents(sanitizer,plugin);
        plugin.getServer().getPluginManager().registerEvents(new WorldRules(sanitizer),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.PlayerRestoreListener(plugin,this),plugin);
        plugin.getServer().getPluginManager().registerEvents(bottles,plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.CombatListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.PlayerEliminationListener(this,itemSerializer),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.DeathBoxListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.MatchTickListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.SpectatorListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.OfflineBodyListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.PreparationFreezeListener(this),plugin);
        mobLoot=new PaperMobLoot(plugin,this);
    }
    public RoomRuntimeService rooms() { return rooms; }
    public void reload() {
        if(lobby.busy())throw new IllegalStateException("大厅仍在施工或保存，请完成后再重载。");
        if(recovery!=null && (!recovery.ready() || !recovery.idle() || !durablePlayers.idleForReload()))throw new IllegalStateException("Recovery/checkpoint completion must finish before reload");
        if(progression!=null && !progression.idle())throw new IllegalStateException("Permanent data operations must finish before reload");
        if(mapAdministration!=null && mapAdministration.busy())throw new IllegalStateException("Map maintenance must finish before reload");
        if(loadouts.busy()) throw new IllegalStateException("Close loadout editors and wait for saves before reload");
        if (rooms != null && !rooms.canReload())
            throw new IllegalStateException("Cannot reload BattleRoyale while rooms or game sessions are active.");
        PaperRecoveryCoordinator previousRecovery=recovery;
        RoomRuntimeService[] prepared = new RoomRuntimeService[1];
        foundation.reload(candidate -> prepared[0] = create(candidate));
        RoomRuntimeService old = rooms;
        rooms = prepared[0];
        if (old != null) old.close();
        if(previousRecovery!=null){previousRecovery.close();recovery.start();}
    }
    private RoomRuntimeService create(ConfigurationSnapshot configuration) {
        if(storageSettings!=null && !storageSettings.equals(configuration.settings().database()))throw new IllegalStateException("Changing database settings requires a server restart");
        if(storage==null){storageSettings=configuration.settings().database();storage=new com.npucraft.battleroyale.storage.RecoveryStorage(new com.npucraft.battleroyale.storage.JdbcStorageProvider(storageSettings),plugin.getLogger()::severe);}
        var progressionConfig=com.npucraft.battleroyale.config.ProgressionConfig.load(plugin);
        var nextProgression=new PaperProgression(plugin,storage,new com.npucraft.battleroyale.storage.JdbcStorageProvider(storageSettings),progressionConfig,configuration.settings().economyProvider());
        var content=new com.npucraft.battleroyale.config.MatchContentLoader(plugin.getDataFolder().toPath(),new NativeLootItems(),itemSerializer::item).load(configuration,true);
        var server = plugin.getServer();
        var players = new PaperPlayers(server, configuration.settings().lobbyWorld(), messages);
        WorldFiles files;
        try {
            var layout = new RuntimeLayout(server.getLevelDirectory(), RuntimeLayout.GAME);
            files = WorldFiles.paper262(plugin.getDataFolder().toPath(), layout,
                    configuration.maps().stream().map(MapTemplate::templatePath).toList(),
                    server.getWorlds().stream().map(world -> world.getWorldPath()).filter(path ->
                            !path.toAbsolutePath().normalize().startsWith(layout.runtimeRoot())).toList());
        } catch (IOException error) { throw new IllegalStateException("Invalid runtime world paths: " + error.getMessage(), error); }
        var scheduler = new PaperScheduler(plugin);
        var worker = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "BattleRoyale-world-io");
            thread.setUncaughtExceptionHandler((t, error) -> messages.runtimeError("World IO uncaught failure", error));
            return thread;
        });
        OnDemandWorldProvider provider;
        try { provider = new OnDemandWorldProvider(files, new PaperWorlds(server, players), scheduler, worker, messages::runtimeError); }
        catch (RuntimeException error) { worker.shutdown(); throw error; }
        var matches = new PaperMatches(plugin, configuration, scheduler, new com.npucraft.battleroyale.zone.RecoveryClock(com.npucraft.battleroyale.zone.GameClock.system()), new java.util.Random(), players,
                loadouts,sanitizer,content,isolation,groundMarker,itemSerializer,bottles,celebrations,spectators,messages);
        var result = new RoomRuntimeService(() -> foundation.state().configuration(), foundation.sessions(),
                scheduler, (room,candidates)->MapSelector.random(new java.util.Random()).select(room,foundation.state().maps().all().stream().filter(m->foundation.state().maps().isAvailable(m.id()) && (mapAdministration==null || !mapAdministration.locked(m.id()))).toList()), provider, players, Clock.systemUTC(), matches);
        if(progression!=null)progression.close();
        progression=nextProgression;matches.progression(progression);
        matches.beforePrepare(lobby::beforeMatchSnapshot);
        if(progressionTask!=null)progressionTask.cancel();
        lobby.reset();
        startupReported=false;
        progressionTask=server.getScheduler().runTaskTimer(plugin,()->{progression.tick(recoveryReady());lobby.tick();
            if(!startupReported && recoveryReady() && progression.ready() && mapAdministration!=null && mapAdministration.ready()){
                startupReported=true;plugin.getLogger().info("BattleRoyale "+plugin.getPluginMeta().getVersion()+" rooms="+rooms.rooms().size()+" maps="+foundation.state().maps().all().size()+" storage="+storageSettings.type()+" schema=V"+storage.schema()+" economy="+progression.economy().active()+" recovery=OK");
                long warnings=diagnostics.collect().checks().stream().filter(c->c.status()!=com.npucraft.battleroyale.admin.DiagnosticsService.Status.OK).count();
                progression.manualReviewCount().whenComplete((count,error)->{long total=warnings+(error!=null||count>0?1:0);if(total>0)plugin.getLogger().warning("Startup completed with "+total+" warning components. MANUAL_REVIEW="+(error==null?count:"unavailable")+". Run /br admin diagnose");});
                if(foundation.state().maps().all().stream().noneMatch(m->foundation.state().maps().isAvailable(m.id())))plugin.getLogger().warning("No valid map templates available. Install templates and run /br admin map validate <map>.");
            }
        },1,20);
        this.matches = matches;
        this.provider = provider;
        recovery=new PaperRecoveryCoordinator(plugin,configuration,storage,durablePlayers,isolation,files,provider,matches,result,foundation.sessions(),sanitizer,recoveryEntities);
        durablePlayers.storage(storage,this::recoveryReady);
        matches.recoveryAccess(this::recoveryReady,storage::healthy,durablePlayers::blocked);
        if(mapAdministration!=null)mapAdministration.close();
        mapAdministration=new PaperMapAdministration(plugin,this,foundation,configuration,content);
        matches.administrationBlocked(this::editing);
        loadouts.replace(content.loadouts());
        this.matchContent=content;
        playerStates.lobby(configuration.settings().lobbyWorld());
        lobby.configure(progressionConfig,configuration.settings().lobbyWorld());
        return result;
    }
    @Override public void close() {
        mobLoot.close();
        lobby.close();
        if(mapAdministration!=null)mapAdministration.close();
        diagnostics.close();
        if(progressionTask!=null)progressionTask.cancel();
        if(progression!=null)progression.close();
        if(recovery!=null)recovery.close();
        if (rooms != null) {
            try { rooms.close(); } catch (Exception error) { messages.runtimeError("Runtime shutdown failed", error); }
        }
        if (provider != null) {
            try {
                if (!provider.awaitFileShutdown(30, java.util.concurrent.TimeUnit.SECONDS))
                    plugin.getLogger().warning("World IO shutdown did not drain within the shutdown boundary; inspect retained runtime markers.");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        foundation.close();
        isolation.close();
        loadouts.close();
        if(recovery!=null)recovery.finishClose();else if(storage!=null)storage.close();
    }
}

