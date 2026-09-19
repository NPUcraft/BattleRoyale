package com.lastsector.paper;
import com.lastsector.service.GameScheduler;
import org.bukkit.plugin.java.JavaPlugin;
/** The sole Bukkit scheduler adapter. All callbacks run on the server thread. */
public final class PaperScheduler implements GameScheduler {
    private final JavaPlugin plugin;
    public PaperScheduler(JavaPlugin plugin) { this.plugin = plugin; }
    @Override public Task repeat(int periodTicks, Runnable action) {
        var task = plugin.getServer().getScheduler().runTaskTimer(plugin, action, periodTicks, periodTicks);
        return task::cancel;
    }
}

