package com.npucraft.battleroyale.paper;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.user.CoinsUser;
import com.npucraft.battleroyale.service.UiText;
import java.util.*;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Lobby wealth leaderboard source: periodically snapshots every CoinsEngine account
 *  (online and offline) into a top list plus a server total. The CoinsEngine per-player
 *  "hidden from tops" preference is deliberately ignored on this private server, so every
 *  account is visible. Read-only; runs on the async scheduler and never touches the main
 *  thread. Stale data is served while the economy is unavailable. */
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
    private volatile long startedAt;
    private volatile boolean running;
    private volatile boolean announced;
    public PaperEconomyTop(JavaPlugin plugin,Supplier<String> currency){this.plugin=plugin;this.currency=currency;}
    /** Called from the regular lobby tick; triggers an async refresh at most every 30s. */
    public void tick(){
        if(running && System.nanoTime()-startedAt>3*REFRESH_NANOS){
            plugin.getLogger().warning("[EconomyTop] refresh appears stuck ("+((System.nanoTime()-startedAt)/1_000_000)+"ms); unblocking");
            running=false;
        }
        if(running || System.nanoTime()-lastRefresh<REFRESH_NANOS)return;
        running=true;startedAt=System.nanoTime();
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
                    if(balance>0)eligible.add(new Row(user.getName(),balance));
                }
                eligible.sort(Comparator.comparingDouble(Row::balance).reversed());
                rows=List.copyOf(eligible.subList(0,Math.min(TOP_SIZE,eligible.size())));
                total=sum;ready=true;lastRefresh=System.nanoTime();
                if(!announced){
                    announced=true;
                    plugin.getLogger().info("[EconomyTop] first snapshot: currency="+name+" accounts="+users.size()
                            +" top="+rows.size()+" total="+PaperEconomyRewards.format(total)+" took="+(System.nanoTime()-startedAt)/1_000_000+"ms");
                }
            } catch(Throwable error) {
                plugin.getLogger().warning("[EconomyTop] refresh failed: "+error.getClass().getSimpleName()+": "+error.getMessage());
            } finally {running=false;}
        });
    }
    /** Empty until the first successful snapshot; the sidebar then hides the section. */
    public List<Row> rows(){return rows;}
    public double total(){return total;}
    public boolean ready(){return ready;}
    /** /br economy list: every account (online and offline) sorted by balance, marked when the
     *  player has the CoinsEngine hide-from-tops flag set. Async load, results on the main thread. */
    public void sendFullList(Player viewer){
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                String name=currency.get();
                if(!CoinsEngineAPI.isLoaded() || name==null || !CoinsEngineAPI.hasCurrency(name)){
                    main(viewer,()->viewer.sendMessage(UiText.error(viewer,"经济插件或货币不可用。","Economy plugin or currency unavailable.")));
                    return;
                }
                var users=new ArrayList<>(CoinsEngineAPI.getUserManager().getDataAccessor().loadAll());
                users.sort(Comparator.comparingDouble((CoinsUser user)->user.getBalanceMap().getOrDefault(name,0.0)).reversed());
                double sum=0;var lines=new ArrayList<String>();
                for(var user:users){
                    double balance=user.getBalanceMap().getOrDefault(name,0.0);sum+=balance;
                    lines.add(user.getName()+"："+PaperEconomyRewards.format(balance)+(user.isHiddenFromTops()?"（已在 CoinsEngine 标记隐藏）":""));
                }
                final double total=sum;
                main(viewer,()->{
                    if(!viewer.isOnline())return;
                    viewer.sendMessage(UiText.message("全服经济 · 全部 "+users.size()+" 个账户（含离线，货币："+name+"）"));
                    for(String line:lines)viewer.sendMessage(UiText.text(line));
                    viewer.sendMessage(UiText.value("合计 "+PaperEconomyRewards.format(total)));
                });
            } catch(Throwable error) {
                plugin.getLogger().warning("[EconomyTop] full list failed: "+error.getClass().getSimpleName()+": "+error.getMessage());
                main(viewer,()->viewer.sendMessage(UiText.error(viewer,"读取全服账户失败，详见服务器日志。","Failed to read accounts; see server log.")));
            }
        });
    }
    private void main(Player viewer,Runnable action){
        if(plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,action);
    }
    public String diagnostics(){return "ready="+ready+" rows="+rows.size()+" total="+PaperEconomyRewards.format(total)+" running="+running+" lastRefreshAgoMs="+(lastRefresh==0?-1:(System.nanoTime()-lastRefresh)/1_000_000);}
}
