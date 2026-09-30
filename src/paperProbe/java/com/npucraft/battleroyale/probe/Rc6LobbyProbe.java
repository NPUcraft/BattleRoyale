package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.LobbySettings;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.progression.LobbyRankingRow;
import com.npucraft.battleroyale.service.PluginRuntime;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Explicitly isolated server/API checks; no claimed native player client or GUI-click validation. */
public final class Rc6LobbyProbe {
    private final JavaPlugin plugin;
    private boolean busy;
    public Rc6LobbyProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc6"),"Explicit rc6 probe flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No native players during isolated probe");
        require(!busy,"Lobby probe already running");
        if(args.length!=2||!Set.of("all","baseline","verify").contains(args[1]))throw new IllegalArgumentException("p26rc6lobby all | baseline | verify");
        busy=true;CompletableFuture<String> work;
        try{work=run(args[1]);}catch(Exception error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((detail,error)->main(()->{
            busy=false;
            if(error==null)sender.sendMessage("RC6 LOBBY "+args[1].toUpperCase(Locale.ROOT)+" SUCCESS "+detail);
            else{sender.sendMessage("RC6 LOBBY "+args[1].toUpperCase(Locale.ROOT)+" FAILED "+error);plugin.getLogger().log(Level.SEVERE,"RC6 lobby probe failed",error);}
            return null;
        }));
    }
    private Plugin battleRoyale(){return Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale"));}
    private Path data(){return battleRoyale().getDataFolder().toPath().toAbsolutePath().normalize();}
    private Path output(String name){return plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("rc6-lobby").resolve(name);}
    private PluginRuntime runtime()throws Exception{
        var field=battleRoyale().getClass().getDeclaredField("runtime");field.setAccessible(true);return (PluginRuntime)field.get(battleRoyale());
    }
    private CompletableFuture<String> run(String operation)throws Exception{
        var settings=LobbySettings.load(YamlConfiguration.loadConfiguration(data().resolve("lobby.yml").toFile()));
        require(settings.buildEnabled(),"Built lobby enabled");var world=Objects.requireNonNull(plugin.getServer().getWorld(settings.world()));
        var marker=new Properties();try(var input=Files.newInputStream(data().resolve("lobby-structure.properties"))){marker.load(input);}
        require("READY".equals(marker.getProperty("state")),"Existing lobby READY");
        var runtime=runtime();
        var warm=new ArrayList<CompletableFuture<?>>();
        for(int x=Math.floorDiv(settings.x()-32,16);x<=Math.floorDiv(settings.x()+32,16);x++)
            for(int z=Math.floorDiv(settings.z()-32,16);z<=Math.floorDiv(settings.z()+32,16);z++)warm.add(world.getChunkAtAsync(x,z,false));
        return waitFor(()->runtime.recoveryReady()&&runtime.progression().ready(),400,"Progression and recovery bootstrap ready")
                .thenCompose(unused->CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)))
                .thenCompose(unused->runtime.progression().lobbyRanking())
                .thenCompose(rows->waitFor(()->matches(world,settings,rows),200,"Production board matches real SQL rows").thenApply(unused->rows))
                .thenCompose(rows->blocks(world,settings).thenCompose(hash->main(()->snapshot(world,settings,rows,hash))))
                .thenCompose(before->{
                    if(operation.equals("baseline"))return main(()->{world.save();return before;}).thenCompose(report->save("baseline.yml",report));
                    if(operation.equals("verify"))return main(()->{var baseline=YamlConfiguration.loadConfiguration(output("baseline.yml").toFile());compare(baseline,before);before.set("restart","owned UUIDs, every blueprint block, marker and backup preserved");return before;}).thenCompose(report->save("restart-report.yml",report));
                    return main(()->lifecycle(world,settings)).thenCompose(future->future)
                            .thenCompose(detail->blocks(world,settings).thenCompose(hash->main(()->{
                                require(before.getString("blocks-sha256").equals(hash),"Every lobby blueprint block unchanged by board lifecycle");
                                require(before.getString("marker-sha256").equals(hash(data().resolve("lobby-structure.properties"))),"READY marker unchanged");
                                require(before.getString("backup-sha256").equals(hash(data().resolve("lobby-structure-original.blocks.gz"))),"Original construction backup unchanged");
                                before.set("lifecycle",detail);before.set("buildings","all blueprint positions unchanged");return before;
                            }))).thenCompose(report->save("report.yml",report));
                });
    }
    private boolean matches(World world,LobbySettings settings,List<LobbyRankingRow> rows){
        var own=entities(world,battleRoyale(),settings);if(own.size()!=PaperLobbyDataBoard.IDS.size()||own.values().stream().anyMatch(list->list.size()!=1))return false;
        var entity=own.getOrDefault("rating",List.of());return entity.size()==1&&entity.getFirst() instanceof TextDisplay display
                &&plain(display.text()).equals(plain(LobbyRankingModel.render(rows,LobbyRankingModel.Status.READY)));
    }
    private YamlConfiguration snapshot(World world,LobbySettings settings,List<LobbyRankingRow> rows,String blockHash)throws Exception{
        var own=entities(world,battleRoyale(),settings);require(own.keySet().equals(PaperLobbyDataBoard.IDS),"Exactly the recognized board ids");
        var report=new YamlConfiguration();report.set("platform",plugin.getServer().getVersion());report.set("world",world.getName());report.set("world-uuid",world.getUID().toString());
        report.set("blocks-sha256",blockHash);report.set("blueprint-positions",LobbyBlueprint.blocks().size());report.set("marker-sha256",hash(data().resolve("lobby-structure.properties")));report.set("backup-sha256",hash(data().resolve("lobby-structure-original.blocks.gz")));
        for(var entry:own.entrySet()){
            require(entry.getValue().size()==1,"Unique board id "+entry.getKey());var entity=entry.getValue().getFirst();
            require(entity.isPersistent()&&entity.isInvulnerable(),"Persistent protected owned entity");
            report.set("entities."+entry.getKey()+".uuid",entity.getUniqueId().toString());report.set("entities."+entry.getKey()+".type",entity.getType().name());
            var at=entity.getLocation();report.set("entities."+entry.getKey()+".position",List.of(at.getX(),at.getY(),at.getZ()));
        }
        var text=(TextDisplay)own.get("rating").getFirst();var lectern=(BlockDisplay)own.get("profile-lectern").getFirst();var hitbox=(Interaction)own.get("profile-interaction").getFirst();
        require(lectern.getBlock().getMaterial()==Material.LECTERN,"Lectern BlockDisplay uses native block data");
        require(hitbox.getInteractionWidth()>1&&hitbox.getInteractionHeight()>1&&hitbox.isResponsive(),"Clickable native Interaction hitbox");
        require(text.getLocation().getX()==settings.x()+26.5&&text.getLocation().getZ()==settings.z()+12.5,"Board beside lobby paths");
        var colors=new HashSet<String>();collect(text.text(),null,colors);require(colors.size()>=3,"Visible board has distinct heading, data and prompt colors");
        report.set("text",plain(text.text()));report.set("colors",new ArrayList<>(colors));report.set("actual-persisted-player-count",rows.size());
        report.set("data-source","BattleRoyale bounded DB worker, current actual persisted profiles; no fixture rows inserted");
        report.set("limits","Native TextDisplay/BlockDisplay/Interaction and Player-interface proxy checks; no native player client rendering or actual profile GUI click.");
        return report;
    }
    private void compare(YamlConfiguration before,YamlConfiguration after){
        require(before.contains("blocks-sha256"),"Saved baseline exists");
        for(String field:List.of("world-uuid","blocks-sha256","marker-sha256","backup-sha256"))require(Objects.equals(before.get(field),after.get(field)),"Restart retains "+field);
        for(String id:PaperLobbyDataBoard.IDS){String path="entities."+id;if(id.equals("rating-en")&&!before.contains(path+".uuid"))continue;require(Objects.equals(before.get(path+".uuid"),after.get(path+".uuid")),"Restart reuses entity UUID "+id);require(Objects.equals(before.get(path+".position"),after.get(path+".position")),"Restart retains entity position "+id);}
    }
    private CompletableFuture<String> lifecycle(World world,LobbySettings original){
        var settings=new LobbySettings(true,original.world(),original.x()-52,original.y(),original.z(),32,1200,false,true,false,true);
        String origin=origin(world,settings);var idKey=new NamespacedKey(plugin,PaperLobbyDataBoard.ID_KEY);var originKey=new NamespacedKey(plugin,PaperLobbyDataBoard.ORIGIN_KEY);
        require(world.getEntities().stream().noneMatch(entity->origin.equals(entity.getPersistentDataContainer().get(originKey,PersistentDataType.STRING))),"Fresh probe-owned origin");
        var boards=new ArrayList<PaperLobbyDataBoard>();var source=new CompletableFuture<List<LobbyRankingRow>>();var calls=new AtomicInteger();var clicks=new AtomicInteger();
        var board=new PaperLobbyDataBoard(plugin,settings,()->{calls.incrementAndGet();return source;},player->clicks.incrementAndGet());boards.add(board);
        board.tick(false);require(calls.get()==0&&board.entityIds().isEmpty(),"Unavailable board does not query or create entities");board.tick(true);
        var work=waitFor(()->board.ready()&&calls.get()==1,100,"Probe board async chunk initialized").thenCompose(unused->main(()->{
            for(int i=0;i<1000;i++)board.tick(true);require(calls.get()==1,"1000 ticks coalesce to one in-flight query");source.complete(List.of());
            var own=entities(world,plugin,settings);var text=(TextDisplay)own.get("rating").getFirst();require(plain(text.text()).contains("暂无玩家数据"),"Empty source displays honest empty state");
            var hitbox=(Interaction)own.get("profile-interaction").getFirst();Location near=hitbox.getLocation().clone();UUID playerId=UUID.randomUUID();
            Player proxy=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->switch(method.getName()){
                case "getWorld"->world;case "getLocation"->near.clone();case "getUniqueId"->playerId;case "getServer"->plugin.getServer();
                case "toString"->"Rc6LobbyPlayerInterfaceProxy";case "hashCode"->playerId.hashCode();case "equals"->self==args[0];
                default->throw new UnsupportedOperationException("Unused proxy method "+method);
            });
            board.interact(new PlayerInteractEntityEvent(proxy,hitbox,EquipmentSlot.OFF_HAND));require(clicks.get()==0,"Off hand cannot double-open profile");
            near.add(20,0,0);board.interact(new PlayerInteractEntityEvent(proxy,hitbox,EquipmentSlot.HAND));require(clicks.get()==0,"Distant interaction rejected");near.subtract(20,0,0);
            var event=new PlayerInteractEntityEvent(proxy,hitbox,EquipmentSlot.HAND);board.interact(event);board.interact(event);require(event.isCancelled()&&clicks.get()==1,"Owned nearby hand click invokes callback once");
            Set<UUID> before=board.entityIds();board.close();require(before.stream().allMatch(id->world.getEntity(id)!=null),"Close retains owned persistent entities");
            require(!world.getChunkAt((settings.x()+26)>>4,(settings.z()+12)>>4).getPluginChunkTickets().contains(plugin),"Close releases probe chunk ticket");
            var reopened=new PaperLobbyDataBoard(plugin,settings,()->CompletableFuture.completedFuture(List.of()),player->{});boards.add(reopened);reopened.tick(true);
            return waitFor(reopened::ready,100,"Reopened board ready").thenCompose(ignored->main(()->{
                require(before.equals(reopened.entityIds()),"Reopen reconciles all recognized entity UUIDs in place");reopened.reconfigure(settings);
                require(before.stream().allMatch(id->world.getEntity(id)!=null),"Same-origin reload keeps owned entities");
                var at=hitbox.getLocation();var duplicate=world.spawn(at,TextDisplay.class);duplicate.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,"rating");duplicate.getPersistentDataContainer().set(originKey,PersistentDataType.STRING,origin);
                var unknown=world.spawn(at,TextDisplay.class);unknown.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,"unknown-probe-id");unknown.getPersistentDataContainer().set(originKey,PersistentDataType.STRING,origin);
                var incomplete=world.spawn(at,TextDisplay.class);incomplete.getPersistentDataContainer().set(originKey,PersistentDataType.STRING,origin);
                var late=new CompletableFuture<List<LobbyRankingRow>>();var deduped=new PaperLobbyDataBoard(plugin,settings,()->late,player->{});boards.add(deduped);deduped.tick(true);
                return waitFor(deduped::ready,100,"Deduplication board ready").thenCompose(done->main(()->{
                    var current=entities(world,plugin,settings);require(current.size()==PaperLobbyDataBoard.IDS.size()&&current.values().stream().allMatch(list->list.size()==1),"Known owned duplicate removed");
                    require(unknown.isValid()&&incomplete.isValid(),"Unknown and incomplete owned-looking tags preserved");
                    var ownedText=(TextDisplay)current.get("rating").getFirst();String prior=plain(ownedText.text());deduped.close();late.complete(List.of());require(prior.equals(plain(ownedText.text())),"Completion after close cannot mutate display");
                    deduped.reconfigure(new LobbySettings(false,settings.world(),settings.x(),settings.y(),settings.z(),32,1200,false,true,false,true));
                    require(entities(world,plugin,settings).isEmpty(),"Disabling board retires only previous origin's known entities");
                    require(unknown.isValid()&&incomplete.isValid(),"Retirement preserves unknown entities");
                    return "async-query=coalesced empty=honest UUIDs=retained duplicate=removed unknown=preserved click=proxy-only tickets=released late-completion=ignored disabled=retired";
                }));
            })).thenCompose(future->future);
        })).thenCompose(future->future);
        return work.whenComplete((unused,error)->main(()->{
            boards.forEach(PaperLobbyDataBoard::close);
            // This namespace/origin was asserted empty before creating fixtures; remove only those fixtures.
            world.getEntities().stream().filter(entity->origin.equals(entity.getPersistentDataContainer().get(originKey,PersistentDataType.STRING))).toList().forEach(Entity::remove);
            return null;
        }));
    }
    private Map<String,List<Entity>> entities(World world,Plugin owner,LobbySettings settings){
        var map=new TreeMap<String,List<Entity>>();var idKey=new NamespacedKey(owner,PaperLobbyDataBoard.ID_KEY);var originKey=new NamespacedKey(owner,PaperLobbyDataBoard.ORIGIN_KEY);String origin=origin(world,settings);
        for(Entity entity:world.getEntities())if(origin.equals(entity.getPersistentDataContainer().get(originKey,PersistentDataType.STRING))){String id=entity.getPersistentDataContainer().get(idKey,PersistentDataType.STRING);if(id!=null&&PaperLobbyDataBoard.IDS.contains(id))map.computeIfAbsent(id,key->new ArrayList<>()).add(entity);}
        return map;
    }
    private static String origin(World world,LobbySettings settings){return world.getUID()+":"+settings.x()+":"+settings.y()+":"+settings.z();}
    private CompletableFuture<Void> waitFor(BooleanSupplier condition,int maximumTicks,String description){
        var future=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();future.complete(null);}else if(++ticks>=maximumTicks){cancel();future.completeExceptionally(new IllegalStateException(description+" timed out"));}}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;
    }
    private CompletableFuture<String> blocks(World world,LobbySettings settings){
        var future=new CompletableFuture<String>();var plan=LobbyBlueprint.blocks();final MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception error){return CompletableFuture.failedFuture(error);}
        new BukkitRunnable(){int cursor;public void run(){try{for(int i=0;i<1500&&cursor<plan.size();i++,cursor++){var at=plan.get(cursor).position();String line=at.x()+","+at.y()+","+at.z()+":"+world.getBlockAt(settings.x()+at.x(),settings.y()+at.y(),settings.z()+at.z()).getBlockData().getAsString()+"\n";digest.update(line.getBytes(StandardCharsets.UTF_8));}if(cursor==plan.size()){cancel();future.complete(HexFormat.of().formatHex(digest.digest()));}}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;
    }
    private CompletableFuture<String> save(String name,YamlConfiguration report){
        Path path=output(name);byte[] bytes=report.saveToString().getBytes(StandardCharsets.UTF_8);
        return CompletableFuture.supplyAsync(()->{try{AtomicFiles.write(path,bytes);return "path="+path;}catch(Exception error){throw new CompletionException(error);}});
    }
    private static void collect(Component text,TextColor inherited,Set<String> colors){require(text.clickEvent()==null&&text.hoverEvent()==null,"Display text creates no click or hover events");TextColor color=text.color()==null?inherited:text.color();if(color!=null)colors.add(color.asHexString());for(var child:text.children())collect(child,color,colors);}
    private static String plain(Component text){return PlainTextComponentSerializer.plainText().serialize(text);}
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private <T> CompletableFuture<T> main(Callable<T> action){var future=new CompletableFuture<T>();Runnable run=()->{try{future.complete(action.call());}catch(Throwable error){future.completeExceptionally(error);}};if(Bukkit.isPrimaryThread())run.run();else plugin.getServer().getScheduler().runTask(plugin,run);return future;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("RC6 lobby assertion: "+message);}
}
