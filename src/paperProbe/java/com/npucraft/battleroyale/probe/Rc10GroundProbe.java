package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.Zone;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Real world, ray tracing, items and durable files; Player calls are interface recorders, not clients. */
public final class Rc10GroundProbe {
    private static final int[] POINT_X={0,256,512,-256};
    private final JavaPlugin plugin;
    private final YamlConfiguration report=new YamlConfiguration();
    private final Set<Long> forced=new HashSet<>();
    private final List<Viewer> viewers=new ArrayList<>();
    private World world;
    private GameSession session;
    private MatchContent content;
    private LootTable table;
    private NamespacedKey marker,pointMarker;
    private PaperGroundSupplies supplies;
    private Gate io;
    private Viewer first,second,observer,spectator,disconnected;
    private boolean busy;
    public Rc10GroundProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc10"),"Explicit isolated rc10 flag");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No native players online");
        require(!busy,"Ground probe already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc10ground all");
        busy=true;CompletableFuture<Void> work;
        try{
            setup();
            work=await(()->Arrays.stream(POINT_X).allMatch(x->world.getChunkAt(x>>4,0).isEntitiesLoaded()),100,"Native fixture entities loaded")
                    .thenRun(this::beforeSeal)
                    .thenCompose(unused->mainFuture(supplies.seal()))
                    .thenRun(this::visibilityAndEligibility)
                    .thenCompose(unused->claimWhileWalkingAway())
                    .thenCompose(unused->blockedAfterClaim())
                    .thenCompose(unused->restart())
                    .thenCompose(unused->unloadedPoint())
                    .thenCompose(unused->stopWhileClaimPending())
                    .thenCompose(unused->recoverAfterStop());
        }catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->finish(sender,error)));
    }
    private void setup(){
        io=new Gate();
        var key=new NamespacedKey("battleroyale_probe","rc10_ground_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
        marker=new NamespacedKey(plugin,"ground_loot_session");pointMarker=new NamespacedKey(plugin,"ground_supply_point");
        for(int center:POINT_X){
            for(int x=center-4;x<=center+4;x++)for(int z=-4;z<=4;z++){
                world.getBlockAt(x,80,z).setType(Material.STONE,false);
                for(int y=81;y<=84;y++)world.getBlockAt(x,y,z).setType(Material.AIR,false);
            }
            force(center>>4,0);
        }
        first=new Viewer(Locale.ENGLISH);second=new Viewer(Locale.SIMPLIFIED_CHINESE);observer=new Viewer(Locale.ENGLISH);
        spectator=new Viewer(Locale.ENGLISH);disconnected=new Viewer(Locale.ENGLISH);
        var room=new RoomDefinition("rc10-ground","Ground supply fixture",1,5,1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(1));
        var map=new MapTemplate("fixture","Generated local ground supply fixture",world.getWorldPath(),new PlayableArea(-1200,1200,-1200,1200));
        session=GameSession.waiting(UUID.randomUUID(),room,Instant.now());
        List.of(first,second,spectator,disconnected).forEach(viewer->session.join(viewer.id));
        session.prepare(map,new Random(19));session.initialZone(new Zone(0,0,1000));
        session.starting(new GameWorld(session.sessionId(),room.id(),world.getName(),world.getWorldPath(),map));
        session.transition(GameState.RUNNING);session.disconnected(disconnected.id);spectator.mode=GameMode.SPECTATOR;
        table=new LootTable("rc10-ground",1,1,List.of(new LootTable.Entry("minecraft:diamond",1,1,1)));
        content=new MatchContent(Map.of(),Map.of(table.id(),table),Map.of());
        supplies=create();for(int x:POINT_X)supplies.plan(x,81,0,table,13+x);
        report.set("platform",plugin.getServer().getVersion());report.set("fixture.world",world.getName());
        report.set("fixture.session",session.sessionId().toString());report.set("fixture.world-uuid",world.getUID().toString());
    }
    private PaperGroundSupplies create(){return new PaperGroundSupplies(plugin,session,content,new NativeLootItems(),marker,io);}
    private void beforeSeal(){
        first.at(0,.5);drive(List.of(first.player),20);
        require(nativeItems().isEmpty()&&world.getEntitiesByClass(Display.class).isEmpty(),"Planning creates no Item or Display entity");
        require(first.particles==0&&first.sounds==0,"Unsealed runtime produces no player signals");
        report.set("unsealed.no-entities-or-signals",true);
    }
    private void visibilityAndEligibility(){
        require(nativeItems().isEmpty(),"Sealing does not create items");
        first.at(0,20.5);first.reset();drive(List.of(first.player),160);
        require(first.particles==384&&first.dust==320&&first.sparkles==64,"One point has exactly 24 particles per ten driven ticks");
        require(first.sounds==1&&first.chimes==1,"Proximity chime is limited to one per 160 driven ticks");
        require(nativeItems().isEmpty()&&world.getEntitiesByClass(Display.class).isEmpty(),"Visible unopened supply remains entity-free");
        report.set("signals.particles-per-160-driven-ticks",first.particles);report.set("signals.chimes-per-160-driven-ticks",first.chimes);
        first.at(0,49.5);first.reset();drive(List.of(first.player),160);
        require(first.particles==0&&first.sounds==0,"Viewers beyond 48 blocks receive no signals");
        for(var viewer:List.of(observer,spectator,disconnected)){viewer.at(0,.5);viewer.reset();}
        drive(List.of(observer.player,spectator.player,disconnected.player),160);
        require(List.of(observer,spectator,disconnected).stream().allMatch(viewer->viewer.particles==0&&viewer.sounds==0),"Observer, spectator and disconnected participant cannot reveal or claim");
        first.at(0,.5);first.reset();first.online=false;drive(List.of(first.player),20);first.online=true;
        first.dead=true;drive(List.of(first.player),20);first.dead=false;
        first.mode=GameMode.CREATIVE;drive(List.of(first.player),20);first.mode=GameMode.SURVIVAL;
        first.otherWorld=plugin.getServer().getWorlds().stream().filter(value->!value.equals(world)).findFirst().orElseThrow();
        drive(List.of(first.player),20);first.otherWorld=null;
        require(first.particles==0&&nativeItems().isEmpty(),"Offline, dead, creative and other-world participant cannot trigger");
        session.eliminate(spectator.id);spectator.mode=GameMode.SURVIVAL;drive(List.of(spectator.player),20);
        require(spectator.particles==0&&nativeItems().isEmpty(),"Eliminated participant cannot trigger");
        // A real block ray trace, with no wall or player simulation substituted for the native geometry.
        first.at(0,3.5);for(int y=81;y<=83;y++)world.getBlockAt(1,y,0).setType(Material.STONE,false);
        drive(List.of(first.player),20);require(nativeItems().isEmpty()&&supplies.diagnostics().contains("claimed=0"),"A wall blocks the first claim");
        for(int y=81;y<=83;y++)world.getBlockAt(1,y,0).setType(Material.AIR,false);
        report.set("eligibility","Only RUNNING-session ALIVE online survival participants in the same world; native ray trace blocks through-wall activation");
    }
    private CompletableFuture<Void> claimWhileWalkingAway(){
        first.at(0,2.5);second.at(0,3.5);first.reset();second.reset();io.pause();
        drive(List.of(first.player,second.player),10);
        require(io.pending()==1&&nativeItems().isEmpty(),"Two contenders queue one durable claim, with no loot before IO");
        first.at(0,25.5);second.at(0,26.5);io.resume();
        return mainFuture(io.barrier()).thenCompose(unused->await(()->{
            supplies.tick(List.of(first.player,second.player));return lootAt(0);
        },100,"Claimed point materializes a chest after contenders walk away")).thenRun(()->{
            var chest=chestFor(0);var stacks=Arrays.stream(chest.getInventory().getContents()).filter(Objects::nonNull).toList();
            require(stacks.size()==1&&stacks.getFirst().getType()==Material.DIAMOND&&stacks.getFirst().getAmount()==1,"One deterministic native stack inside the chest");
            require(chest.getInventory().getHolder() instanceof org.bukkit.block.Chest,"Materialized block is an openable chest");
            require(chest.customName()!=null,"Chest carries a display name");
            int announcements=first.messages.size()+second.messages.size();require(announcements==1,"Only one contender receives an opening message");
            var message=first.messages.isEmpty()?second.messages.getFirst():first.messages.getFirst();
            require(message.contains("Field supplies discovered")||message.contains("已发现野外补给"),"Opening message follows the chosen contender's locale");
            first.at(0,.5);second.at(0,.5);drive(List.of(first.player,second.player),160);
            require(lootAt(0)&&first.messages.size()+second.messages.size()==1,"Repeated and competing ticks never duplicate chests or messages");
            chest.getInventory().clear();drive(List.of(first.player,second.player),20);
            require(chestFor(0)==null&&world.getBlockAt(POINT_X[0],81,0).getType().isAir(),"Emptied supply chest is removed by the runtime");
            report.set("claim.competing-players-single-chest",true);report.set("claim.walking-away-still-materializes",true);report.set("claim.emptied-chest-removed",true);
        }).thenCompose(unused->snapshot()).thenAccept(saved->require(saved.claimed().equals(Set.of(0)),"First claim persisted before native chest spawn"));
    }
    private CompletableFuture<Void> blockedAfterClaim(){
        second.at(1,2.5);second.reset();io.pause();drive(List.of(second.player),10);
        require(io.pending()==1,"Second point has one claim write");
        world.getBlockAt(POINT_X[1],81,0).setType(Material.STONE,false);second.at(1,20.5);io.resume();
        return mainFuture(io.barrier()).thenRun(()->{
            drive(List.of(second.player),20);require(nativeItems().isEmpty()&&supplies.diagnostics().contains("pending=1"),"Occupied claimed point is deferred without losing it");
            world.getBlockAt(POINT_X[1],81,0).setType(Material.AIR,false);supplies.tick(List.of(second.player));
            require(lootAt(1),"Chest appears at the original point when the space becomes safe");
            require(second.messages.size()==1&&second.messages.getFirst().contains("已发现野外补给"),"Chinese opening message is rendered per viewer");
            chestFor(1).getInventory().clear();drive(List.of(second.player),20);
            require(chestFor(1)==null,"Emptied second chest is swept");report.set("claim.temporary-obstruction-defers-without-reclaim",true);
        }).thenCompose(unused->snapshot()).thenAccept(saved->require(saved.claimed().equals(Set.of(0,1)),"Both independent claims are durable"));
    }
    private CompletableFuture<Void> restart(){
        return mainFuture(supplies.stop()).thenRun(()->{
            String name=world.getName();UUID id=world.getUID();NamespacedKey key=world.getKey();releaseForced();
            require(plugin.getServer().unloadWorld(world,true),"Dedicated world saved and unloaded");
            world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key)));
            require(world.getName().equals(name)&&world.getUID().equals(id),"Native save/reload retains world identity");
            viewers.forEach(viewer->viewer.location.setWorld(world));for(int x:POINT_X)force(x>>4,0);
            supplies=create();supplies.recover();
        }).thenCompose(unused->await(()->{
            supplies.tick(List.of());return supplies.planned()==POINT_X.length&&Arrays.stream(POINT_X).allMatch(x->world.getChunkAt(x>>4,0).isEntitiesLoaded());
        },200,"Recovery reads all committed points")).thenRun(()->{
            first.at(0,.5);second.at(1,.5);drive(List.of(first.player,second.player),160);
            require(nativeItems().isEmpty(),"Picked-up claims do not respawn after actual world save/unload/reload");
            require(chestFor(0)==null&&chestFor(1)==null,"Claimed points stay chest-free after recovery");
            report.set("recovery.native-world-save-reload",true);report.set("recovery.picked-up-items-not-recreated",true);
        });
    }
    private CompletableFuture<Void> unloadedPoint(){
        int x=POINT_X[3]>>4;unforce(x,0);
        return await(()->{world.unloadChunk(x,0,true);return !world.isChunkLoaded(x,0);},100,"Unclaimed target chunk unloads").thenRun(()->{
            first.at(3,.5);first.reset();drive(List.of(first.player),160);
            require(!world.isChunkLoaded(x,0)&&nativeItems().isEmpty()&&first.particles==0&&first.sounds==0,"Proximity does not load, reveal or claim an unloaded chunk");
            force(x,0);report.set("chunks.unloaded-point-not-forced-or-claimed",true);
        }).thenCompose(unused->await(()->world.getChunkAt(x,0).isEntitiesLoaded(),100,"Target entities reload"));
    }
    private CompletableFuture<Void> stopWhileClaimPending(){
        first.at(2,2.5);io.pause();drive(List.of(first.player),10);
        require(io.pending()==1&&nativeItems().isEmpty(),"Pending cancellation test really waits on durable IO");
        var stopped=supplies.stop();require(!stopped.isDone()&&supplies.stop()==stopped,"Stop is idempotent and waits for the claim writer");
        drive(List.of(first.player),20);io.resume();
        return mainFuture(stopped).thenCompose(unused->delay(3)).thenRun(()->{
            drive(List.of(first.player),40);require(nativeItems().isEmpty(),"No delayed item after stop even when the claim completes");
            report.set("stop.pending-claim-drained-without-late-item",true);
        }).thenCompose(unused->snapshot()).thenAccept(saved->require(saved.claimed().equals(Set.of(0,1,2)),"Cancelled committed claim stays consumed to prevent recovery duplication"));
    }
    private CompletableFuture<Void> recoverAfterStop(){
        supplies=create();supplies.recover();
        return await(()->{supplies.tick(List.of());return supplies.planned()==POINT_X.length;},100,"Stopped runtime can be recovered from its plan").thenRun(()->{
            first.at(2,.5);drive(List.of(first.player),40);require(nativeItems().isEmpty(),"Committed cancelled claim cannot resurrect");
            first.at(3,.5);
        }).thenCompose(unused->await(()->{supplies.tick(List.of(first.player));return lootAt(3);},100,"Previously unloaded unclaimed negative-coordinate point can still open"))
                .thenCompose(unused->snapshot()).thenAccept(saved->{
                    require(saved.claimed().equals(Set.of(0,1,2,3)),"Claims remain independent across all lifecycle changes");
                    require(lootAt(3),"Final unclaimed point produces exactly one chest");
                    require(Arrays.stream(chestFor(3).getInventory().getContents()).filter(Objects::nonNull).count()==1,"Final chest carries exactly one stack");
                    report.set("recovery.unclaimed-negative-coordinate-point-opens-once",true);
                    report.set("diagnostics",supplies.diagnostics());chestFor(3).getInventory().clear();drive(List.of(first.player),20);
                });
    }
    private CompletableFuture<GroundSupplyLedger.Snapshot> snapshot(){
        var ledger=new GroundSupplyLedger(world.getWorldPath(),session.sessionId(),world.getUID());
        return mainFuture(CompletableFuture.supplyAsync(()->{
            try{return ledger.read().orElseThrow();}catch(Exception error){throw new CompletionException(error);}
        },io));
    }
    private List<Item> nativeItems(){return new ArrayList<>(world.getEntitiesByClass(Item.class));}
    /** Materialization is a chest block, not item entities: marker PDC on the block state must match session+point. */
    private Chest chestFor(int point){
        var state=world.getBlockAt(POINT_X[point],81,0).getState();
        return state instanceof Chest chest
                &&Integer.valueOf(point).equals(chest.getPersistentDataContainer().get(pointMarker,PersistentDataType.INTEGER))
                &&session.sessionId().toString().equals(chest.getPersistentDataContainer().get(marker,PersistentDataType.STRING))?chest:null;
    }
    private boolean lootAt(int point){var chest=chestFor(point);return chest!=null&&Arrays.stream(chest.getInventory().getContents()).anyMatch(Objects::nonNull);}
    private void drive(List<Player> audience,int ticks){for(int i=0;i<ticks;i++)supplies.tick(audience);}
    private void force(int x,int z){
        var chunk=world.getChunkAt(x,z);long key=key(x,z);if(!chunk.isForceLoaded()){chunk.setForceLoaded(true);forced.add(key);}chunk.getEntities();
    }
    private void unforce(int x,int z){if(forced.remove(key(x,z)))world.setChunkForceLoaded(x,z,false);}
    private void releaseForced(){for(long key:List.copyOf(forced))world.setChunkForceLoaded((int)key,(int)(key>>32),false);forced.clear();}
    private static long key(int x,int z){return ((long)z<<32)|(x&0xffffffffL);}
    private void finish(CommandSender sender,Throwable error){
        if(io!=null)io.resume();
        var drained=supplies==null?CompletableFuture.<Void>completedFuture(null):supplies.stop();
        mainFuture(drained).whenComplete((unused,stopError)->main(()->{
            Throwable failure=error!=null?error:stopError;
            try{
                if(world!=null){releaseForced();require(world.getPluginChunkTickets().isEmpty(),"No plugin chunk tickets leaked");require(plugin.getServer().unloadWorld(world,true),"Final fixture unload succeeds");}
            }catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            report.set("status",failure==null?"passed":"failed");if(failure!=null)report.set("failure",failure.toString());
            report.set("limits",List.of("Dedicated generated flat local world; no production map or native player used.","Native item entities, PDC, ray tracing, chunk unload and world save/reload use actual Paper APIs.","Player location/state, particle/sound/message reception are interface recorders; this is not a client-rendering, pickup packet or complete-match test.","The 160-tick signal budget is advanced through production tick calls; it does not claim eight seconds of wall-clock playback."));
            var file=plugin.getDataFolder().toPath().resolve("rc10/ground-report.yml");
            try{AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));}
            catch(Throwable save){if(failure==null)failure=save;else failure.addSuppressed(save);}
            if(io!=null)io.close();busy=false;
            if(failure==null)sender.sendMessage("RC10 GROUND SUCCESS no-idle-items=OK durable-claim=OK proximity=OK recovery=OK stop-drain=OK path="+file.toAbsolutePath());
            else{sender.sendMessage("RC10 GROUND FAILED "+failure);plugin.getLogger().log(Level.SEVERE,"RC10 ground probe failed",failure);}
        }));
    }
    private CompletableFuture<Void> await(BooleanSupplier condition,int maxTicks,String description){
        var future=new CompletableFuture<Void>();
        new BukkitRunnable(){int ticks;@Override public void run(){
            try{if(condition.getAsBoolean()){cancel();future.complete(null);}else if(++ticks>=maxTicks){cancel();future.completeExceptionally(new IllegalStateException(description+" timed out; "+(supplies==null?"no-runtime":supplies.diagnostics())));}}
            catch(Throwable error){cancel();future.completeExceptionally(error);}
        }}.runTaskTimer(plugin,1,1);return future;
    }
    private CompletableFuture<Void> delay(int ticks){
        var future=new CompletableFuture<Void>();plugin.getServer().getScheduler().runTaskLater(plugin,()->future.complete(null),ticks);return future;
    }
    private <T> CompletableFuture<T> mainFuture(CompletableFuture<T> future){
        var result=new CompletableFuture<T>();future.whenComplete((value,error)->main(()->{if(error==null)result.complete(value);else result.completeExceptionally(error);}));return result;
    }
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("RC10 ground assertion: "+message);}
    private final class Viewer {
        final UUID id=UUID.randomUUID();final Player player;final Locale locale;final List<String> messages=new ArrayList<>();
        Location location=new Location(world,.5,81,.5);World otherWorld;GameMode mode=GameMode.SURVIVAL;
        boolean online=true,dead;int particles,dust,sparkles,sounds,chimes;
        Viewer(Locale locale){
            this.locale=locale;viewers.add(this);
            player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->switch(method.getName()){
                case "getUniqueId"->id;case "getWorld"->otherWorld==null?world:otherWorld;case "getLocation"->location.clone();
                case "getEyeLocation"->location.clone().add(0,1.62,0);case "isOnline"->online;case "isDead"->dead;case "getGameMode"->mode;case "locale"->locale;
                case "spawnParticle"->{require(args[4] instanceof Integer,"Expected native coordinate particle overload");int count=(int)args[4];particles+=count;
                    if(args[0]==Particle.DUST){dust+=count;require(args[9] instanceof Particle.DustOptions,"Colored supply dust uses native DustOptions");}
                    else if(args[0]==Particle.END_ROD)sparkles+=count;else throw new IllegalStateException("Unexpected supply particle "+args[0]);yield null;}
                case "playSound"->{sounds++;if(args[1]==Sound.BLOCK_AMETHYST_BLOCK_CHIME)chimes++;yield null;}
                case "sendMessage"->{for(Object arg:args)if(arg instanceof Component component)messages.add(PlainTextComponentSerializer.plainText().serialize(component));yield null;}
                case "equals"->self==args[0];case "hashCode"->id.hashCode();case "toString"->"Rc10GroundPlayerInterfaceRecorder";
                default->throw new IllegalStateException("Unexpected ground Player API "+method);
            });
        }
        void at(int point,double offset){location=new Location(world,POINT_X[point]+offset,81,.5);}
        void reset(){particles=0;dust=0;sparkles=0;sounds=0;chimes=0;messages.clear();}
    }
    /** Delays only the dedicated fixture's IO, so cancellation is observed before the real atomic write. */
    private static final class Gate implements Executor,AutoCloseable {
        private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"rc10-ground-probe-io");thread.setDaemon(true);return thread;});
        private final Queue<Runnable> pending=new ArrayDeque<>();private boolean paused;
        @Override public synchronized void execute(Runnable task){if(paused)pending.add(task);else worker.execute(task);}
        synchronized void pause(){paused=true;}
        synchronized int pending(){return pending.size();}
        synchronized void resume(){paused=false;for(Runnable task;(task=pending.poll())!=null;)worker.execute(task);}
        CompletableFuture<Void> barrier(){return CompletableFuture.runAsync(()->{},worker);}
        @Override public void close(){resume();worker.shutdown();}
    }
}