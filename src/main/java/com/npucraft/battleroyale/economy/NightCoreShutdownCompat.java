package com.npucraft.battleroyale.economy;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Remove the optional CoinsEngine expansion while PlaceholderAPI still owns its manager. */
public final class NightCoreShutdownCompat implements Listener {
    private final JavaPlugin owner;
    public NightCoreShutdownCompat(JavaPlugin owner){this.owner=owner;}
    @EventHandler(priority=EventPriority.LOWEST)
    public void disabling(PluginDisableEvent event){
        if(event.getPlugin().getName().equals("PlaceholderAPI"))removeCoinsExpansion();
    }
    public void stopping(){if(owner.getServer().isStopping())removeCoinsExpansion();}
    private void removeCoinsExpansion(){
        var manager=owner.getServer().getPluginManager();
        var coins=manager.getPlugin("CoinsEngine");
        if(!(coins instanceof JavaPlugin plugin)||!plugin.isEnabled()||!manager.isPluginEnabled("PlaceholderAPI")||!manager.isPluginEnabled("nightcore"))return;
        try{
            if(su.nightexpress.nightcore.integration.placeholder.PAPI.removeExpansions(plugin))
                owner.getLogger().info("CoinsEngine 占位符已在 PlaceholderAPI 停止前安全注销。");
        }catch(LinkageError|RuntimeException failure){
            owner.getLogger().log(java.util.logging.Level.WARNING,"无法提前注销 CoinsEngine 占位符；请检查依赖停服日志。",failure);
        }
    }
}
