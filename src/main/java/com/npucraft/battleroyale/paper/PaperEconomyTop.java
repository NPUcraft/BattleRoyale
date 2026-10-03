package com.npucraft.battleroyale.paper;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import java.util.*;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Lobby wealth leaderboard source: periodically snapshots every CoinsEngine account
 *  (online and offline) into a top list plus a server total. Honours the player's own
 *  "hidden from tops" preference. Read-only; runs on the async scheduler and never
 *  touches the main thread. Stale data is served while the economy is unavailable. */
public final class PaperEconomyTop {
    public static final int TOP_SIZE=5;
    public record Row(String name,double balance) {}
    private static final long REFRESH_NANOS=30_000_000_000L;
    private final JavaPlugin plugin;
    private final Supplier<String> currency;
    private volatile List<Row> rows=List.of();
    private volatile double total;
    private volatile boolean ready;
    private volatile long lastRefresh;
    private volatile boolean running;
    public PaperEconomyTop(JavaPlugin plugin,Supplier<String> currency){this.plugin=plugin;this.currency=currency;}
    /** Called from the regular lobby tick; triggers an async refresh at most every 30s. */
    public void tick(){
        if(running || System.nanoTime()-lastRefresh<REFRESH_NANOS)return;
        running=true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                if(!CoinsEngineAPI.isLoaded())return;
                String name=currency.get();
                if(name==null || !CoinsEngineAPI.hasCurrency(name))return;
                var users=CoinsEngineAPI.getUserManager().getDataAccessor().loadAll();
                double sum=0;
                var eligible=new ArrayList<Row>();
                for(var user:users){
                    double balance=user.getBalanceMap().getOrDefault(name,0.0);
                    sum+=balance;
                    if(!user.isHiddenFromTops())eligible.add(new Row(user.getName(),balance));
                }
                eligible.sort(Comparator.comparingDouble(Row::balance).reversed());
                rows=List.copyOf(eligible.subList(0,Math.min(TOP_SIZE,eligible.size())));
                total=sum;ready=true;lastRefresh=System.nanoTime();
            } catch(Throwable error) {
                plugin.getLogger().warning("[EconomyTop] refresh failed: "+error.getClass().getSimpleName());
            } finally {running=false;}
        });
    }
    /** Empty until the first successful snapshot; the sidebar then hides the section. */
    public List<Row> rows(){return rows;}
    public double total(){return total;}
    public boolean ready(){return ready;}
}
