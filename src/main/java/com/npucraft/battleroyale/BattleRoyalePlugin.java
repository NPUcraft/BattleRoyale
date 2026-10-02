package com.npucraft.battleroyale;

import com.npucraft.battleroyale.command.BattleRoyaleCommand;
import com.npucraft.battleroyale.config.ConfigurationLoader;
import com.npucraft.battleroyale.service.FoundationService;
import com.npucraft.battleroyale.service.MessageService;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.listener.PlayerConnectionListener;
import com.npucraft.battleroyale.session.SessionManager;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.util.Objects;

/** Paper lifecycle adapter. Domain and configuration work is delegated to injected services. */
public final class BattleRoyalePlugin extends JavaPlugin {
    private FoundationService foundation;
    private MessageService messages;
    private PluginRuntime runtime;
    private com.npucraft.battleroyale.economy.NightCoreShutdownCompat economyShutdown;

    @Override public void onLoad() {
        // No resources or Paper API calls in a constructor.
    }
    @Override public void onEnable() {
        I18n.install();
        messages = new MessageService(getLogger());
        economyShutdown=new com.npucraft.battleroyale.economy.NightCoreShutdownCompat(this);
        getServer().getPluginManager().registerEvents(economyShutdown,this);
        try {
            for (String file : new String[]{"lobby.yml", "ranking.yml", "cosmetics.yml", "config.yml", "rooms.yml", "maps.yml", "zones.yml", "loadouts.yml", "loot-tables.yml"}) {
                if (!Files.exists(getDataFolder().toPath().resolve(file))) saveResource(file, false);
            }
            // Only seed bundled example metadata while that example remains configured.
            // Removing a map must not recreate its directory on every restart.
            var maps = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    getDataFolder().toPath().resolve("maps.yml").toFile()).getConfigurationSection("maps");
            if (maps != null) for (String id : maps.getKeys(false)) {
                if (!id.matches("[a-zA-Z0-9_-]+")) continue;
                String resource = "map-data/" + id + "/loot.yml";
                if (Files.exists(getDataFolder().toPath().resolve(resource))) continue;
                try (var bundled = getResource(resource)) {
                    if (bundled != null) saveResource(resource, false);
                }
            }
            var migrated=new com.npucraft.battleroyale.config.ConfigMigrationService(getDataFolder().toPath()).migrate();
            if(!migrated.isEmpty())getLogger().info("Configuration migration v1 -> v2 backed up: "+migrated);
            reloadConfig();
            var loader = new ConfigurationLoader(getDataFolder().toPath(),true);
            foundation = new FoundationService(loader::load, new SessionManager());
            runtime = new PluginRuntime(this, foundation, messages);
            runtime.reload();
            var handler = new BattleRoyaleCommand(foundation, runtime, messages, getPluginMeta().getVersion());
            var command = Objects.requireNonNull(getCommand("battleroyale"), "plugin.yml must declare battleroyale");
            command.setExecutor(handler);
            command.setTabCompleter(handler);
            getServer().getPluginManager().registerEvents(new PlayerConnectionListener(runtime), this);
            getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.ChatStyleListener(this), this);
            getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.PvPProtectionListener(runtime), this);
            getServer().getPluginManager().registerEvents(new com.npucraft.battleroyale.listener.PaperFunItems(this,runtime), this);
            runtime.beginRecovery();
            messages.loaded(foundation.state().configuration());
        } catch (Exception exception) {
            messages.startupFailed(exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        try {
            if(economyShutdown!=null)economyShutdown.stopping();
            if (runtime != null) runtime.close();
            else if (foundation != null) foundation.close();
            if (messages != null) messages.stopped();
        } finally { I18n.close(); }
    }
}
