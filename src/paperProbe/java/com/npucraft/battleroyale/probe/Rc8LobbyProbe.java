package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.LobbySettings;
import com.npucraft.battleroyale.config.LobbySidebarSettings;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.progression.LobbyRankingRow;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.session.GameState;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;

/** Native entities/items/scoreboards and explicit Player API recorders; never a claimed client test. */
public final class Rc8LobbyProbe {
    private final JavaPlugin plugin;private boolean busy;
    public Rc8LobbyProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc8"),"Explicit rc8 flag");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No native players in isolated test");
        require(!busy,"Probe already active");
        if(args.length!=2||!Set.of("all","baseline","verify").contains(args[1]))throw new IllegalArgumentException("p26rc8lobby all | baseline | verify");
        busy=true;CompletableFuture<String> work;
        try{work=run(args[1]);}catch(Exception error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((result,error)->main(()->{
            busy=false;
            if(error==null)sender.sendMessage("RC8 LOBBY "+args[1].toUpperCase(Locale.ROOT)+" SUCCESS "+result);
            else{sender.sendMessage("RC8 LOBBY "+args[1].toUpperCase(Locale.ROOT)+" FAILED "+error);plugin.getLogger().log(Level.SEVERE,"RC8 lobby probe failed",error);}
            return null;
        }));
    }
    private Plugin owner(){return Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale"));}
    private Path data(){return owner().getDataFolder().toPath().toAbsolutePath().normalize();}
    private Path output(String name){return plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("rc8-lobby").resolve(name);}
    private PluginRuntime runtime()throws Exception{return (PluginRuntime)field(owner(),"runtime");}
    private static Object field(Object instance,String name)throws Exception{var field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    private CompletableFuture<String> run(String mode)throws Exception{
        var settings=LobbySettings.load(YamlConfiguration.loadConfiguration(data().resolve("lobby.yml").toFile()));require(settings.buildEnabled(),"Built lobby enabled");
        var world=Objects.requireNonNull(plugin.getServer().getWorld(settings.world()));var runtime=runtime();
        var warm=new ArrayList<CompletableFuture<Chunk>>();var tickets=new ArrayList<Chunk>();
        for(int x=Math.floorDiv(settings.x()-32,16);x<=Math.floorDiv(settings.x()+32,16);x++)for(int z=Math.floorDiv(settings.z()-32,16);z<=Math.floorDiv(settings.z()+32,16);z++)warm.add(world.getChunkAtAsync(x,z,false));
        var work=waitFor(()->runtime.recoveryReady()&&runtime.progression().ready()&&runtime.lobby().ready(),400,"Lobby/data ready")
                .thenCompose(unused->CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)))
                .thenCompose(unused->main(()->{for(var loaded:warm){var chunk=loaded.join();require(chunk!=null,"Existing lobby observation chunk");if(chunk.addPluginChunkTicket(plugin))tickets.add(chunk);chunk.getEntities();}return null;}))
                .thenCompose(unused->runtime.progression().lobbyRanking())
                .thenCompose(rows->waitFor(()->matches(world,settings,rows),200,"Both ranking languages reflect actual SQL").whenComplete((unused,error)->{if(error!=null)rankingDiagnostics(world,settings,rows);}).thenApply(unused->rows))
                .thenCompose(rows->blocks(world,settings).thenCompose(hash->main(()->{
                    var report=snapshot(world,settings,hash);report.set("actual-ranking-rows",rows.size());
                    if(mode.equals("all")){
                        var legacyFile=plugin.getDataFolder().toPath().resolve("rc4/baseline.yml");
                        if(Files.isRegularFile(legacyFile)){
                            var legacy=YamlConfiguration.loadConfiguration(legacyFile.toFile());
                            for(String id:List.of("solo","duo","squad","welcome")){
                                require(Objects.equals(legacy.getString("labels."+id+".uuid"),report.getString("entities.label-"+id+".uuid")),"Upgrade retains original Chinese label UUID "+id);
                                var originalPosition=legacy.getDoubleList("labels."+id+".position");require(originalPosition.size()==5,"Historical label coordinates exist "+id);
                                require(originalPosition.subList(0,3).equals(report.getDoubleList("entities.label-"+id+".position")),"Upgrade retains original Chinese label position "+id);
                            }
                            report.set("upgrade.original-chinese-labels-retained",true);
                        }
                        languages(runtime,world,report);sidebar(world,report);items(runtime,world,report);
                    }
                    if(mode.equals("verify"))compare(YamlConfiguration.loadConfiguration(output("baseline.yml").toFile()),report);
                    if(mode.equals("baseline"))world.save();
                    report.set("status","passed");report.set("limits",List.of("Generated local sample only; no complete remote map or multiplayer match.","Real Paper entities/items/scoreboards; Player visibility and locale switching use interface recorders, not native clients.","Actual persisted SQL rows only; no ranking or economy account is inserted."));
                    return report;
                }))).thenCompose(report->save(mode.equals("baseline")?"baseline.yml":mode.equals("verify")?"restart-report.yml":"report.yml",report));
        return work.handle((result,error)->main(()->{for(var chunk:tickets)chunk.removePluginChunkTicket(plugin);tickets.clear();if(error!=null)throw new CompletionException(error);return result;})).thenCompose(value->value);
    }
    private Map<String,Entity> entities(World world,LobbySettings settings){
        var result=new TreeMap<String,Entity>();var label=new NamespacedKey(owner(),"lobby_structure_label");var board=new NamespacedKey(owner(),PaperLobbyDataBoard.ID_KEY);var originKey=new NamespacedKey(owner(),PaperLobbyDataBoard.ORIGIN_KEY);
        String origin=world.getUID()+":"+settings.x()+":"+settings.y()+":"+settings.z();
        for(var entity:world.getEntities()){
            String id=entity.getPersistentDataContainer().get(label,PersistentDataType.STRING);String key=null;
            if(id!=null&&Set.of("solo","duo","squad","welcome","solo-en","duo-en","squad-en","welcome-en").contains(id))key="label-"+id;
            else if(origin.equals(entity.getPersistentDataContainer().get(originKey,PersistentDataType.STRING))){id=entity.getPersistentDataContainer().get(board,PersistentDataType.STRING);if(id!=null&&PaperLobbyDataBoard.IDS.contains(id))key="board-"+id;}
            if(key!=null){require(result.putIfAbsent(key,entity)==null,"One persistent entity per semantic/language id: "+key);}
        }
        return result;
    }
    private boolean matches(World world,LobbySettings settings,List<LobbyRankingRow> rows){
        var all=entities(world,settings);if(all.size()!=12)return false;
        for(var entry:Map.of("board-rating",Locale.CHINESE,"board-rating-en",Locale.ENGLISH).entrySet()){
            if(!(all.get(entry.getKey()) instanceof TextDisplay text)||!plain(text.text()).equals(plain(LobbyRankingModel.render(rows,LobbyRankingModel.Status.READY,entry.getValue()))))return false;
        }
        return true;
    }
    private void rankingDiagnostics(World world,LobbySettings settings,List<LobbyRankingRow> rows){
        var all=entities(world,settings);plugin.getLogger().warning("RC8 lobby observation ids="+all.keySet()+" expectedCount=12 rows="+rows.size());
        for(var entry:Map.of("board-rating",Locale.CHINESE,"board-rating-en",Locale.ENGLISH).entrySet()){
            String actual=all.get(entry.getKey()) instanceof TextDisplay text?plain(text.text()):"<missing>";
            plugin.getLogger().warning("RC8 lobby observation "+entry.getKey()+" actual="+actual.replace("\n"," | ")+" expected="+plain(LobbyRankingModel.render(rows,LobbyRankingModel.Status.READY,entry.getValue())).replace("\n"," | "));
        }
    }
    private YamlConfiguration snapshot(World world,LobbySettings settings,String hash)throws Exception{
        var report=new YamlConfiguration();report.set("platform",plugin.getServer().getVersion());report.set("world-uuid",world.getUID().toString());report.set("blocks-sha256",hash);
        report.set("marker-sha256",hash(data().resolve("lobby-structure.properties")));report.set("backup-sha256",hash(data().resolve("lobby-structure-original.blocks.gz")));
        var all=entities(world,settings);require(all.size()==12,"Exactly eight labels and four data-board entities");
        var localeKey=new NamespacedKey(owner(),LobbyDisplayAudience.LANGUAGE_KEY);
        for(var entry:all.entrySet()){
            var entity=entry.getValue();var at=entity.getLocation();String path="entities."+entry.getKey();
            report.set(path+".uuid",entity.getUniqueId().toString());report.set(path+".position",List.of(at.getX(),at.getY(),at.getZ()));
            require(entity.isPersistent(),"Persistent display variant");
            if(entity instanceof TextDisplay text){
                boolean english=entry.getKey().endsWith("-en");require(!text.isVisibleByDefault(),"Language variant hidden by default");
                require((english?"en":"zh").equals(text.getPersistentDataContainer().get(localeKey,PersistentDataType.STRING)),"Explicit language marker");
                report.set(path+".text",plain(text.text()));
                if(english&&entry.getKey().startsWith("label-"))require(plain(text.text()).codePoints().noneMatch(Rc8LobbyProbe::han),"English built-in label has no Chinese text");
            }
        }
        return report;
    }
    private void languages(PluginRuntime runtime,World world,YamlConfiguration report)throws Exception{
        var structure=(LobbyDisplayAudience)field(field(runtime.lobby(),"structure"),"audience");
        var board=(LobbyDisplayAudience)field(field(runtime.lobby(),"dataBoard"),"audience");
        var settings=LobbySettings.load(YamlConfiguration.loadConfiguration(data().resolve("lobby.yml").toFile()));var all=entities(world,settings);
        var viewer=new Viewer(world,Locale.SIMPLIFIED_CHINESE);var original=viewer.world;
        try{
            for(Locale locale:List.of(Locale.SIMPLIFIED_CHINESE,Locale.TRADITIONAL_CHINESE,Locale.ENGLISH,Locale.GERMAN)){
                viewer.locale=locale;structure.update(viewer.player);board.update(viewer.player);
                long shown=all.entrySet().stream().filter(entry->entry.getValue() instanceof TextDisplay).filter(entry->Boolean.TRUE.equals(viewer.visible.get(entry.getValue().getUniqueId()))).count();
                require(shown==5,"Exactly five localized text displays visible for "+locale);
                for(var entry:all.entrySet())if(entry.getValue() instanceof TextDisplay)require(viewer.visible.get(entry.getValue().getUniqueId())==(!entry.getKey().endsWith("-en")==locale.getLanguage().equals("zh")),"Mutually exclusive viewer language "+entry.getKey());
            }
            viewer.world=plugin.getServer().getWorlds().stream().filter(value->!value.equals(original)).findFirst().orElseThrow();
            structure.update(viewer.player);board.update(viewer.player);require(viewer.visible.values().stream().noneMatch(Boolean.TRUE::equals),"Other-world viewers see no lobby language group");
            viewer.world=original;structure.update(viewer.player);board.update(viewer.player);
            require(viewer.visible.values().stream().filter(Boolean.TRUE::equals).count()==5,"Returning viewer receives one group");
        }finally{structure.tick();board.tick();}
        report.set("language-audience","zh_CN/zh_TW Chinese; en/de English; locale changes and world changes select exactly five texts; native entities with Player interface recorders");
    }
    private void sidebar(World world,YamlConfiguration report){
        var viewer=new Viewer(world,Locale.ENGLISH);var previous=viewer.scoreboard;var failures=new ArrayList<Throwable>();
        var controller=new PaperLobbySidebar(plugin.getServer().getScoreboardManager(),new LobbySidebarSettings(true,"大逃杀",8),failures::add);
        var rooms=List.of(new LobbySidebarModel.RoomView("solo","Solo",2,24,4,GameState.WAITING,-1),new LobbySidebarModel.RoomView("duo","双人组队",4,24,4,GameState.COUNTDOWN,12),new LobbySidebarModel.RoomView("squad","Squad",8,32,16,GameState.RUNNING,-1));
        try{
            var page=LobbySidebarModel.page(rooms,14,0,8,viewer.locale);controller.update(viewer.player,true,page);
            var objective=viewer.scoreboard.getObjective(PaperLobbySidebar.OBJECTIVE);require(plain(objective.displayName()).equals("BattleRoyale"),"English default sidebar title");
            require(page.lines().size()==5&&viewer.scoreboard.getTeams().size()==15&&viewer.scoreboard.getTeams().stream().allMatch(team->team.getEntries().size()==1),"Compact five visible lines with stable unique team entries");
            require(Objects.equals(objective.numberFormat(),NumberFormat.blank()),"Native blank number format hides score numbers");
            int count=0;for(String entry:viewer.scoreboard.getEntries())if(objective.getScore(entry).isScoreSet())count++;require(count==5,"Exactly five actual sidebar scores");
            require(page.lines().stream().map(Rc8LobbyProbe::plain).anyMatch(value->value.contains("Duo  4/24 · 12s")),"Compact countdown with English default room");
            viewer.locale=Locale.TRADITIONAL_CHINESE;controller.update(viewer.player,true,LobbySidebarModel.page(rooms,14,0,8,viewer.locale));
            require(viewer.scoreboard.getObjective(PaperLobbySidebar.OBJECTIVE)==objective&&plain(objective.displayName()).equals("大逃杀"),"Locale refresh reuses owned native scoreboard");
            require(plain(viewer.scoreboard.getTeam("br_line_2").prefix()).contains("12秒"),"Chinese countdown in same room row");
            controller.hide(viewer.player);require(viewer.scoreboard==previous,"Original native board restored");require(failures.isEmpty(),"No sidebar controller failures");
        }finally{controller.close();}
        report.set("sidebar","3 rooms / 5 lines; native title, prefixes, score count, hidden numbers, locale update and original ownership restore");
    }
    private void items(PluginRuntime runtime,World world,YamlConfiguration report)throws Exception{
        var english=new Viewer(world,Locale.ENGLISH);var chinese=new Viewer(world,Locale.TRADITIONAL_CHINESE);var factory=PaperLobby.class.getDeclaredMethod("configuredItem",Player.class,Material.class,String.class,List.class);factory.setAccessible(true);
        for(var definition:runtime.progression().config().menu().values()){
            var item=(ItemStack)factory.invoke(null,english.player,definition.material(),definition.name(),definition.lore());require(plain(item.getItemMeta().displayName()).equals(LobbyText.defaultLabel(english.player,definition.name())),"Native English menu item name");
        }
        var custom=(ItemStack)factory.invoke(null,english.player,Material.PAPER,"自定义 Solo",List.of("Player <red> &c"));require(plain(custom.getItemMeta().displayName()).equals("自定义 Solo"),"Custom configured name preserved");
        var original=new ItemStack(Material.DIAMOND,3);UUID id=UUID.randomUUID(),session=UUID.randomUUID();
        var en=PaperLobby.createQueueExitItem(owner(),id,session,original,english.locale);var zh=PaperLobby.createQueueExitItem(owner(),id,session,original,chinese.locale);
        require(plain(en.getItemMeta().displayName()).equals("Leave room")&&plain(zh.getItemMeta().displayName()).equals("退出房间"),"Native leave-bed display language");
        var key=new NamespacedKey(owner(),"queue_exit_original");require(Arrays.equals(en.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.BYTE_ARRAY),zh.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.BYTE_ARRAY)),"Language does not change original item payload");
        require(ItemStack.deserializeBytes(QueueExitPayload.decode(en.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.BYTE_ARRAY)).original()).equals(original),"Native original bytes survive localized wrapper");
        report.set("items","Configured menu defaults localized; custom values literal; English/Chinese native exit wrappers share identical original payload");
    }
    private final class Viewer {
        final UUID id=UUID.randomUUID();final Map<UUID,Boolean> visible=new HashMap<>();final Player player;
        Locale locale;World world;Scoreboard scoreboard=plugin.getServer().getScoreboardManager().getNewScoreboard();
        Viewer(World world,Locale locale){this.world=world;this.locale=locale;player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->switch(method.getName()){
            case "getUniqueId"->id;case "getWorld"->this.world;case "locale"->this.locale;case "getScoreboard"->scoreboard;
            case "setScoreboard"->{scoreboard=(Scoreboard)args[0];yield null;}
            case "showEntity","hideEntity"->{visible.put(((Entity)args[1]).getUniqueId(),method.getName().equals("showEntity"));yield null;}
            case "getServer"->plugin.getServer();case "toString"->"Rc8LobbyPlayerInterfaceRecorder";case "hashCode"->id.hashCode();case "equals"->self==args[0];
            default->throw new UnsupportedOperationException("Unneeded viewer method "+method);
        });}
    }
    private void compare(YamlConfiguration before,YamlConfiguration after){
        require(before.getConfigurationSection("entities")!=null,"Saved baseline available");
        for(String field:List.of("world-uuid","blocks-sha256","marker-sha256","backup-sha256"))require(Objects.equals(before.get(field),after.get(field)),"Restart retains "+field);
        require(before.getConfigurationSection("entities").getKeys(false).equals(after.getConfigurationSection("entities").getKeys(false)),"Restart adds no duplicate language group");
        for(String id:before.getConfigurationSection("entities").getKeys(false))for(String field:List.of("uuid","position"))require(Objects.equals(before.get("entities."+id+"."+field),after.get("entities."+id+"."+field)),"Restart retains "+id+" "+field);
    }
    private CompletableFuture<String> blocks(World world,LobbySettings settings){
        var future=new CompletableFuture<String>();var plan=LobbyBlueprint.blocks();final MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception error){return CompletableFuture.failedFuture(error);}
        new BukkitRunnable(){int cursor;public void run(){try{for(int i=0;i<1500&&cursor<plan.size();i++,cursor++){var at=plan.get(cursor).position();digest.update((at.x()+","+at.y()+","+at.z()+":"+world.getBlockAt(settings.x()+at.x(),settings.y()+at.y(),settings.z()+at.z()).getBlockData().getAsString()+"\n").getBytes(StandardCharsets.UTF_8));}if(cursor==plan.size()){cancel();future.complete(HexFormat.of().formatHex(digest.digest()));}}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;
    }
    private CompletableFuture<Void> waitFor(BooleanSupplier condition,int max,String name){var future=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();future.complete(null);}else if(++ticks>=max){cancel();future.completeExceptionally(new IllegalStateException(name+" timed out"));}}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;}
    private CompletableFuture<String> save(String name,YamlConfiguration report){Path path=output(name);byte[] bytes=report.saveToString().getBytes(StandardCharsets.UTF_8);return CompletableFuture.supplyAsync(()->{try{AtomicFiles.write(path,bytes);return "path="+path;}catch(Exception error){throw new CompletionException(error);}});}
    private <T> CompletableFuture<T> main(Callable<T> action){var future=new CompletableFuture<T>();Runnable run=()->{try{future.complete(action.call());}catch(Throwable error){future.completeExceptionally(error);}};if(Bukkit.isPrimaryThread())run.run();else plugin.getServer().getScheduler().runTask(plugin,run);return future;}
    private static boolean han(int code){return Character.UnicodeScript.of(code)==Character.UnicodeScript.HAN;}
    private static String plain(Component text){return PlainTextComponentSerializer.plainText().serialize(text);}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("RC8 lobby assertion: "+message);}
}
