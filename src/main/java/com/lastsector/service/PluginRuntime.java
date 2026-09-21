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
    public void joined(org.bukkit.entity.Player player){if(!matches.joined(player))isolation.retry(player.getUniqueId());if(!isolation.blocked(player.getUniqueId()))messages.deliverOfflineResult(player);}
    public CelebrationEffects celebrations() {return celebrations;}
    public void deferRestore(java.util.UUID session,java.util.UUID player) {isolation.defer(session,player);}
    public org.bukkit.Location lobbySpawn() {return java.util.Objects.requireNonNull(plugin.getServer().getWorld(foundation.state().configuration().settings().lobbyWorld())).getSpawnLocation();}
    public PaperDamageProvenance provenance() { return provenance; }
    private final LoadoutEditor loadouts;
    private final WorldSanitizer sanitizer;
    private final org.bukkit.NamespacedKey groundMarker;
    private final PaperPlayerIsolation playerStates;
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
        isolation=new com.lastsector.player.PlayerIsolation<>(playerStates,(id,error)->messages.runtimeError("Player restoration pending: " + id,error));
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
        if(loadouts.busy()) throw new IllegalStateException("Close loadout editors and wait for saves before reload");
        if (rooms != null && !rooms.canReload())
            throw new IllegalStateException("Cannot reload LastSector while rooms or game sessions are active.");
        RoomRuntimeService[] prepared = new RoomRuntimeService[1];
        foundation.reload(candidate -> prepared[0] = create(candidate));
        RoomRuntimeService old = rooms;
        rooms = prepared[0];
        if (old != null) old.close();
    }
    private RoomRuntimeService create(ConfigurationSnapshot configuration) {
        var content=new com.lastsector.config.MatchContentLoader(plugin.getDataFolder().toPath(),new NativeLootItems(),itemSerializer::item).load(configuration);
        var server = plugin.getServer();
        var players = new PaperPlayers(server, configuration.settings().lobbyWorld(), messages);
        WorldFiles files;
        try {
            files = new WorldFiles(plugin.getDataFolder().toPath(), configuration.settings().runtimeDirectory(),
                    server.getWorldContainer().toPath(), configuration.maps().stream().map(MapTemplate::templatePath).toList(),
                    server.getWorlds().stream().map(world -> world.getWorldFolder().toPath()).toList());
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
        var matches = new PaperMatches(plugin, configuration, scheduler, com.lastsector.zone.GameClock.system(), new java.util.Random(), players,
                loadouts,sanitizer,content,isolation,groundMarker,itemSerializer,bottles,celebrations,spectators,messages);
        var result = new RoomRuntimeService(() -> foundation.state().configuration(), foundation.sessions(),
                scheduler, MapSelector.random(new java.util.Random()), provider, players, Clock.systemUTC(), matches);
        this.matches = matches;
        this.provider = provider;
        loadouts.replace(content.loadouts());
        playerStates.lobby(configuration.settings().lobbyWorld());
        return result;
    }
    @Override public void close() {
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
    }
}

