package com.lastsector.service;

import com.lastsector.config.ConfigurationSnapshot;
import com.lastsector.map.*;
import com.lastsector.paper.*;
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
    private OnDemandWorldProvider provider;
    private final NativeItemSerializer itemSerializer=new NativeItemSerializer();
    private final PaperDamageProvenance provenance=new PaperDamageProvenance();
    private final StoredExperienceBottles bottles;
    private final CelebrationEffects celebrations;
    private final PaperSpectators spectators;
    public PaperSpectators spectators(){return spectators;}
    public void joined(org.bukkit.entity.Player player){if(!recoveryReady()){player.kick(net.kyori.adventure.text.Component.text("LastSector is still recovering sessions. Please reconnect shortly."));return;}var previous=matches.participant(player.getUniqueId());
        // A playerdata acknowledgement is authoritative even if a crash preceded SQL APPLIED/delete.
        // Never replace that acknowledged Lobby inventory with an older active-session checkpoint.
        if(previous!=null && previous.session.state()==com.lastsector.session.GameState.RUNNING && durablePlayers.wasRestored(player)){
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
    private com.lastsector.storage.RecoveryStorage storage;
    private PaperRecoveryCoordinator recovery;
    private com.lastsector.storage.StorageSettings storageSettings;
    public boolean recoveryReady(){return recovery!=null && recovery.ready();}
    public String storageDiagnostics(){return storage==null?"storage=initializing":storage.diagnostics();}
    public String recoveryDiagnostics(){return recovery==null?"bootstrap=INITIALIZING":recovery.diagnostics();}
    public String recoverySession(java.util.UUID id){return recovery==null?"recovery=INITIALIZING":recovery.sessionDiagnostics(id);}
    public void beginRecovery(){recovery.start();}
    private final com.lastsector.player.PlayerIsolation<com.lastsector.player.MatchPlayerSnapshot,com.lastsector.loadout.LoadoutDefinition> isolation;
    public LoadoutEditor loadouts() { return loadouts; }
    public boolean pendingRestore(java.util.UUID player) { return isolation.blocked(player); }
    public void restorePlayer(java.util.UUID player) { isolation.retry(player); }
    public PaperMatches matches() { return matches; }
    public PluginRuntime(JavaPlugin plugin, FoundationService foundation, MessageService messages) {
        this.plugin = plugin; this.foundation = foundation; this.messages = messages;
        bottles=new StoredExperienceBottles(plugin);celebrations=new CelebrationEffects(plugin);
        groundMarker=new org.bukkit.NamespacedKey(plugin,"ground_loot_session");
        sanitizer=new WorldSanitizer(groundMarker);
        loadouts=new LoadoutEditor(plugin,itemSerializer);
        playerStates=new PaperPlayerIsolation(plugin,itemSerializer);
        durablePlayers=new PaperDurablePlayers(plugin,playerStates);
        isolation=new com.lastsector.player.PlayerIsolation<>(durablePlayers,(id,error)->messages.runtimeError("Player restoration pending: " + id,error));
        isolation.durability(durablePlayers);
        plugin.getServer().getPluginManager().registerEvents(recoveryEntities,plugin);
        spectators=new PaperSpectators(plugin,isolation,()->matches,messages);
        plugin.getServer().getPluginManager().registerEvents(loadouts,plugin);
        plugin.getServer().getPluginManager().registerEvents(sanitizer,plugin);
        plugin.getServer().getPluginManager().registerEvents(new WorldRules(sanitizer),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.PlayerRestoreListener(plugin,this),plugin);
        plugin.getServer().getPluginManager().registerEvents(bottles,plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.CombatListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.PlayerEliminationListener(this,itemSerializer),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.DeathBoxListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.MatchTickListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.SpectatorListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.OfflineBodyListener(this),plugin);
        plugin.getServer().getPluginManager().registerEvents(new com.lastsector.listener.PreparationFreezeListener(this),plugin);
    }
    public RoomRuntimeService rooms() { return rooms; }
    public void reload() {
        if(recovery!=null && (!recovery.ready() || !recovery.idle() || !durablePlayers.idleForReload()))throw new IllegalStateException("Recovery/checkpoint completion must finish before reload");
        if(loadouts.busy()) throw new IllegalStateException("Close loadout editors and wait for saves before reload");
        if (rooms != null && !rooms.canReload())
            throw new IllegalStateException("Cannot reload LastSector while rooms or game sessions are active.");
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
        if(storage==null){storageSettings=configuration.settings().database();storage=new com.lastsector.storage.RecoveryStorage(new com.lastsector.storage.JdbcStorageProvider(storageSettings),plugin.getLogger()::severe);}
        var content=new com.lastsector.config.MatchContentLoader(plugin.getDataFolder().toPath(),new NativeLootItems(),itemSerializer::item).load(configuration);
        var server = plugin.getServer();
        var players = new PaperPlayers(server, configuration.settings().lobbyWorld(), messages);
        WorldFiles files;
        try {
            files = new WorldFiles(plugin.getDataFolder().toPath(), configuration.settings().runtimeDirectory(),
                    server.getWorldContainer().toPath(), configuration.maps().stream().map(MapTemplate::templatePath).toList(),
                    server.getWorlds().stream().map(world -> world.getWorldFolder().toPath()).filter(path->!path.toAbsolutePath().normalize().startsWith(configuration.settings().runtimeDirectory()) || path.toAbsolutePath().normalize().equals(configuration.settings().runtimeDirectory())).toList());
        } catch (IOException error) { throw new IllegalStateException("Invalid runtime world paths: " + error.getMessage(), error); }
        var scheduler = new PaperScheduler(plugin);
        var worker = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "LastSector-world-io");
            thread.setUncaughtExceptionHandler((t, error) -> messages.runtimeError("World IO uncaught failure", error));
            return thread;
        });
        OnDemandWorldProvider provider;
        try { provider = new OnDemandWorldProvider(files, new PaperWorlds(server, players), scheduler, worker, messages::runtimeError); }
        catch (RuntimeException error) { worker.shutdown(); throw error; }
        var matches = new PaperMatches(plugin, configuration, scheduler, new com.lastsector.zone.RecoveryClock(com.lastsector.zone.GameClock.system()), new java.util.Random(), players,
                loadouts,sanitizer,content,isolation,groundMarker,itemSerializer,bottles,celebrations,spectators,messages);
        var result = new RoomRuntimeService(() -> foundation.state().configuration(), foundation.sessions(),
                scheduler, MapSelector.random(new java.util.Random()), provider, players, Clock.systemUTC(), matches);
        this.matches = matches;
        this.provider = provider;
        recovery=new PaperRecoveryCoordinator(plugin,configuration,storage,durablePlayers,isolation,files,provider,matches,result,foundation.sessions(),sanitizer,recoveryEntities);
        durablePlayers.storage(storage,this::recoveryReady);
        matches.recoveryAccess(this::recoveryReady,storage::healthy,durablePlayers::blocked);
        loadouts.replace(content.loadouts());
        playerStates.lobby(configuration.settings().lobbyWorld());
        return result;
    }
    @Override public void close() {
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

