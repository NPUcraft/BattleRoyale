package com.npucraft.battleroyale.paper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.npucraft.battleroyale.service.UiText;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.user.CoinsUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** High-risk global economy reset for the CoinsEngine currency. In-game flow: the admin types
 *  /br economy reset, then /br economy reset confirm within 60 seconds. Execution snapshots
 *  every account (online and offline) into a restorable JSON backup plus an audit log entry
 *  before zeroing balances; online players are re-zeroed through the API so their live cache
 *  matches the database. Only the coinsengine provider is supported. */
public final class PaperEconomyReset {
    private static final long CONFIRM_WINDOW_NANOS=60_000_000_000L;
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin;
    private final java.util.function.Supplier<String> currency;
    private final Map<UUID,Long> pending=new ConcurrentHashMap<>();
    /** Currency is read lazily from the live configuration each time the reset executes. */
    public PaperEconomyReset(JavaPlugin plugin,java.util.function.Supplier<String> currency){this.plugin=plugin;this.currency=currency;}
    /** First step: registers the confirmation window and explains the risk. */
    public void request(Player admin) {
        pending.put(admin.getUniqueId(),System.nanoTime()+CONFIRM_WINDOW_NANOS);
        admin.sendMessage(UiText.warning(admin,
                "你正在请求【全服经济重置】：所有玩家（含离线）的余额将清零。这是不可逆操作。",
                "You are requesting a GLOBAL economy reset: all balances (including offline players) will be zeroed. This cannot be undone."));
        admin.sendMessage(UiText.warning(admin,
                "系统会先自动生成余额备份文件。确认请输入 /br economy reset confirm（60 秒内有效）。",
                "A balance backup is generated automatically. To proceed type /br economy reset confirm (within 60 seconds)."));
        plugin.getLogger().warning("[EconomyReset] "+admin.getName()+" requested a global economy reset; awaiting confirm.");
    }
    /** Second step: executes when the same admin confirms inside the window. */
    public void confirm(Player admin) {
        Long until=pending.remove(admin.getUniqueId());
        if(until==null || System.nanoTime()>until) {
            admin.sendMessage(UiText.error(admin,"没有待确认的重置请求，请先输入 /br economy reset。","No pending reset request; start with /br economy reset."));
            return;
        }
        execute(admin,currency.get());
    }
    private void execute(Player admin,String currencyName) {
        if(!CoinsEngineAPI.isLoaded() || !CoinsEngineAPI.hasCurrency(currencyName)) {
            admin.sendMessage(UiText.error(admin,"经济插件或货币不可用，已取消。","Economy plugin or currency unavailable; cancelled."));
            return;
        }
        var currency=CoinsEngineAPI.getCurrency(currencyName);
        var manager=CoinsEngineAPI.getUserManager();
        // Flush pending per-user writes first so the snapshot below is the true current state.
        manager.saveDirty();
        admin.sendMessage(UiText.message(admin,"正在备份并重置全服余额，请稍候……","Backing up and resetting all balances, please wait..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try {
                var users=new ArrayList<>(manager.getDataAccessor().loadAll());
                Path backupDir=plugin.getDataFolder().toPath().resolve("economy-backups");
                Files.createDirectories(backupDir);
                String stamp=STAMP.format(Instant.now());
                Path backup=backupDir.resolve("coins-"+stamp+".json");
                writeBackup(backup,currencyName,users);
                for(var user:users)user.resetBalance(currency);
                manager.getDataAccessor().update(users);
                audit(backupDir.resolve("reset-audit.log"),admin,users.size(),backup);
                int reset=users.size();
                Bukkit.getScheduler().runTask(plugin,()->{
                    int online=0;
                    for(var target:Bukkit.getOnlinePlayers()){CoinsEngineAPI.setBalance(target.getUniqueId(),currencyName,0.0);online++;}
                    admin.sendMessage(UiText.success(admin,
                            "全服经济已重置：共 "+reset+" 个账户（在线 "+online+" 人实时清零）。备份："+backup.getFileName(),
                            "Global economy reset: "+reset+" accounts ("+online+" online zeroed live). Backup: "+backup.getFileName()));
                    for(var target:Bukkit.getOnlinePlayers())
                        if(!target.equals(admin))target.sendMessage(UiText.message("§e全服经济已被管理员重置，余额清零。"));
                    plugin.getLogger().warning("[EconomyReset] Global reset done by "+admin.getName()+": accounts="+reset+" online="+online+" backup="+backup.getFileName());
                });
            } catch(Exception error) {
                plugin.getLogger().severe("[EconomyReset] Failed: "+error);
                Bukkit.getScheduler().runTask(plugin,()->admin.sendMessage(UiText.error(admin,
                        "重置失败，未执行任何清零。详见服务器日志。","Reset failed; nothing was zeroed. See server log.")));
            }
        });
    }
    private void writeBackup(Path backup,String currency,List<CoinsUser> users) throws Exception {
        var gson=new GsonBuilder().setPrettyPrinting().create();
        var root=new com.google.gson.JsonObject();
        root.addProperty("type","battleroyale-economy-backup");
        root.addProperty("currency",currency);
        root.addProperty("created",Instant.now().toString());
        root.addProperty("accounts",users.size());
        var array=new com.google.gson.JsonArray();
        for(var user:users){
            var entry=new com.google.gson.JsonObject();
            entry.addProperty("uuid",user.getId().toString());
            entry.addProperty("name",user.getName());
            var balances=new com.google.gson.JsonObject();
            user.getBalanceMap().forEach(balances::addProperty);
            entry.add("balances",balances);
            array.add(entry);
        }
        root.add("accounts-data",array);
        Files.writeString(backup,gson.toJson(root),StandardCharsets.UTF_8);
    }
    private void audit(Path log,Player admin,int accounts,Path backup) {
        String line=Instant.now()+" admin="+admin.getName()+"("+admin.getUniqueId()+") accounts="+accounts+" backup="+backup.getFileName()+"\n";
        try {Files.writeString(log,line,StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}
        catch(Exception ignored) {/* Audit best-effort; the backup itself is the durable record. */}
    }
}
