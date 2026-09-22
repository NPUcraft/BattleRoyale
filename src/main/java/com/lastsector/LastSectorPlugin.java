package com.lastsector;

import com.lastsector.command.LastSectorCommand;
import com.lastsector.config.ConfigurationLoader;
import com.lastsector.service.FoundationService;
import com.lastsector.service.MessageService;
import com.lastsector.service.PluginRuntime;
import com.lastsector.listener.PlayerConnectionListener;
import com.lastsector.session.SessionManager;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.util.Objects;

/** Paper lifecycle adapter. Domain and configuration work is delegated to injected services. */
public final class LastSectorPlugin extends JavaPlugin {
    private FoundationService foundation;
    private MessageService messages;
    private PluginRuntime runtime;

    @Override public void onLoad() {
        // No resources or Paper API calls in a constructor.
    }
    @Override public void onEnable() {
        messages = new MessageService(getLogger());
        try {
            for (String file : new String[]{"config.yml", "rooms.yml", "maps.yml", "zones.yml", "loadouts.yml", "loot-tables.yml", "map-data/city/loot.yml", "map-data/desert/loot.yml"}) {
                if (!Files.exists(getDataFolder().toPath().resolve(file))) saveResource(file, false);
            }
            var loader = new ConfigurationLoader(getDataFolder().toPath());
            foundation = new FoundationService(loader::load, new SessionManager());
            runtime = new PluginRuntime(this, foundation, messages);
            runtime.reload();
            var handler = new LastSectorCommand(foundation, runtime, messages, getPluginMeta().getVersion());
            var command = Objects.requireNonNull(getCommand("lastsector"), "plugin.yml must declare lastsector");
            command.setExecutor(handler);
            command.setTabCompleter(handler);
            getServer().getPluginManager().registerEvents(new PlayerConnectionListener(runtime), this);
            getServer().getPluginManager().registerEvents(new com.lastsector.listener.PvPProtectionListener(runtime), this);
            runtime.beginRecovery();
            messages.loaded(foundation.state().configuration());
        } catch (Exception exception) {
            messages.startupFailed(exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (runtime != null) runtime.close();
        else if (foundation != null) foundation.close();
        if (messages != null) messages.stopped();
    }
}
