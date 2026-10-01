package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.combat.ProtectionWindow;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/** Real Paper world/container/entity API checks. The only Player used is an interface recorder. */
public final class Rc5Probe {
    private final JavaPlugin plugin;
    private boolean busy;
    private World world;
    private WorldSanitizer sanitizer;
    private PaperAutoContainerLoot automatic;
    private PaperAirdrops drops;
    private final YamlConfiguration report=new YamlConfiguration();
    private final UUID sessionId=UUID.randomUUID();
    private final Map<String,LootTable> tables=Map.of("basic",new LootTable("basic",2,2,List.of(new LootTable.Entry("minecraft:bread",1,3,3))));
    public Rc5Probe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc5"),"Explicit isolated rc5 flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real players in isolated fixture");
        require(!busy,"Probe not already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc5 all");
        busy=true;CompletableFuture<Void> work;
        try{
            NamespacedKey key=new NamespacedKey("battleroyale_probe","rc5_"+UUID.randomUUID().toString().replace("-",""));
            world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
            world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);
            report.set("platform",plugin.getServer().getVersion());report.set("world",world.getName());report.set("world-path",world.getWorldPath().toString());
            report.set("limits",List.of("Dedicated generated flat test world; not the remote Survival-Main map.","No real player, client rendering, inventory click, live match or proxy network roundtrip tested.","Navigation uses a Player interface recorder; particle payloads are submitted to the real Paper World API."));
            navigation();containers(key);work=airdrops();
        }catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            CompletableFuture<Void> drained=drops==null?CompletableFuture.completedFuture(null):drops.stop();
            drained.whenComplete((ignored,stopError)->main(()->{
                Throwable failure=error==null?stopError:error;
                try{
                    if(automatic!=null)automatic.close();
                    if(world!=null){if(sanitizer!=null)sanitizer.remove(world.getUID());require(tickets()==0,"No remaining probe chunk tickets");require(plugin.getServer().unloadWorld(world,true),"Unload dedicated fixture normally");}
                    if(failure==null)report.set("status","passed");else report.set("status","failed");
                    Path file=plugin.getDataFolder().toPath().resolve("rc5/report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
                    if(failure==null)sender.sendMessage("RC5 ALL SUCCESS navigation=OK containers=OK airdrops=OK ending=OK path="+file.toAbsolutePath());
                    else throw new CompletionException(failure);
                }catch(Throwable failed){sender.sendMessage("RC5 ALL FAILED "+failed);plugin.getLogger().log(Level.SEVERE,"RC5 ALL FAILED",failed);}
                finally{busy=false;}
            }));
        }));
    }
    private void navigation(){
        Zone current=new Zone(100,-200,50),next=new Zone(90,-190,20);
        var component=PaperZoneUi.navigationMessage(ZoneNavigation.guide(current,next,170,-200,0),current,next);
        // rc.7 removed the circle-center coordinates; the live format is direction + next-boundary distance only.
        require(plain(component).equals("→ 下圈边界 60 米"),"Chinese next-boundary direction and distance without coordinates");
        var messages=new ArrayList<Component>();var particles=new ArrayList<Particle>();UUID viewerId=UUID.randomUUID();
        Player viewer=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()){
            case "getUniqueId"->viewerId;
            case "getLocation"->new Location(world,499,80,499,0,0);
            case "sendActionBar"->{messages.add((Component)args[0]);yield null;}
            case "spawnParticle"->{
                Particle type=(Particle)args[0];particles.add(type);require(((Integer)args[4])==1,"Single particle per sample");
                double x=(Double)args[1],y=(Double)args[2],z=(Double)args[3];
                if(type==Particle.DUST){var dust=(Particle.DustOptions)args[9];require(dust.getColor().asRGB()==Color.fromRGB(80,170,255).asRGB(),"Blue boundary dust color");world.spawnParticle(type,x,y,z,1,0,0,0,0,dust);}
                else{require(type==Particle.END_ROD,"Bright outline particle");world.spawnParticle(type,x,y,z,1,0,0,0,0);}
                yield null;
            }
            case "toString"->"rc5-interface-viewer";
            default->throw new IllegalStateException("Unexpected viewer API "+method.getName());
        });
        Server server=(Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class<?>[]{Server.class},(proxy,method,args)->{
            if(method.getName().equals("getPlayer"))return viewerId.equals(args[0])?viewer:null;
            throw new IllegalStateException("Unexpected server API "+method.getName());
        });
        var settings=new ZoneUiSettings(false,5,true,"END_ROD",5,64,2.5,1.5,3,6,20,true,5,true);
        var ui=new PaperZoneUi(server,settings);var zone=new ZoneRuntime(new Zone(0,0,500),profile(),new Random(1),0);
        double border=world.getWorldBorder().getSize();Location borderCenter=world.getWorldBorder().getCenter();
        ui.render(viewer,zone,0);require(particles.size()==20&&particles.containsAll(List.of(Particle.DUST,Particle.END_ROD)),"Both particle styles share cap 20");
        require(!plain(messages.getLast()).isEmpty(),"Navigation sent through actionbar API");ui.detach(viewerId);require(plain(messages.getLast()).isEmpty(),"Navigation detached without bossbar");
        ui.render(viewer,zone,5);int emitted=particles.size();ui.render(viewer,zone,10,2,0,1,true);
        require(plain(messages.getLast()).isEmpty()&&particles.size()==emitted,"Spectators clear navigation and get no wall particles");ui.close();
        require(world.getWorldBorder().getSize()==border&&world.getWorldBorder().getCenter().equals(borderCenter),"Real world border unchanged");
        report.set("navigation.message",plain(component));report.set("navigation.particle-cap",20);report.set("navigation.real-world-particle-payloads",true);report.set("navigation.proxy-detach-and-spectator-clearing",true);
    }
    private void containers(NamespacedKey worldKey){
        for(int x=0;x<32;x++)for(int z=0;z<16;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        block(2,2,Material.CHEST);block(4,2,Material.BARREL);block(6,2,Material.BLUE_SHULKER_BOX);block(8,2,Material.BARREL);
        block(12,2,Material.BARREL);block(12,6,Material.CHEST);
        Block left=block(15,2,Material.CHEST),right=block(16,2,Material.CHEST);
        chest(left,org.bukkit.block.data.type.Chest.Type.LEFT);chest(right,org.bukkit.block.data.type.Chest.Type.RIGHT);
        require(((Chest)left.getState()).getInventory().getSize()==54,"Cross-chunk double chest formed");
        sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot"));sanitizer.register(world,sessionId,error->{throw new CompletionException(error);});
        inventory(8,2).setItem(0,new ItemStack(Material.DIAMOND,7));
        var reserved=(Container)world.getBlockAt(12,81,2).getState();reserved.getPersistentDataContainer().set(new NamespacedKey(plugin,"airdrop"),PersistentDataType.STRING,"reserved");reserved.update(false,false);
        var manual=new ManualScheduler();automatic=auto(manual);
        Block placed=world.getBlockAt(10,81,2);var replaced=placed.getState();placed.setType(Material.CHEST,false);
        Player actor=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->method.getName().equals("toString")?"rc5-placement-recorder":null);
        automatic.placed(new BlockPlaceEvent(placed,replaced,placed.getRelative(BlockFace.DOWN),new ItemStack(Material.CHEST),actor,true,EquipmentSlot.HAND));
        Block dispenser=block(10,6,Material.DISPENSER);Directional direction=(Directional)dispenser.getBlockData();direction.setFacing(BlockFace.EAST);dispenser.setBlockData(direction,false);
        block(11,6,Material.PURPLE_SHULKER_BOX);automatic.dispensed(new BlockDispenseEvent(dispenser,new ItemStack(Material.PURPLE_SHULKER_BOX),new Vector(1,0,0)));
        manual.ticks(512);
        for(int x:new int[]{2,4,6,15})require(amount(inventory(x,2),Material.BREAD)==6,"Exactly two rolls in generated container x="+x);
        require(((Chest)left.getState()).getInventory().getSize()==54&&amount(inventory(15,2),Material.BREAD)==6,"Double chest is filled once, not once per half");
        require(amount(inventory(8,2),Material.DIAMOND)==7&&amount(inventory(8,2),Material.BREAD)==0,"Nonempty player storage preserved");
        for(int[] at:new int[][]{{10,2},{11,6},{12,2},{12,6}})require(inventory(at[0],at[1]).isEmpty(),"Placed/dispensed/airdrop/configured container excluded "+Arrays.toString(at));
        NamespacedKey processed=new NamespacedKey(plugin,"container_loot_done");
        for(int x:new int[]{15,16})require(sessionId.toString().equals(((Container)world.getBlockAt(x,81,2).getState()).getPersistentDataContainer().get(processed,PersistentDataType.STRING)),"Both double-chest halves marked");
        for(int x:new int[]{2,4,6,15})inventory(x,2).clear();
        String diagnostics=automatic.diagnostics();automatic.close();automatic=null;sanitizer.remove(world.getUID());
        require(plugin.getServer().unloadWorld(world,true),"Actual container world unload with save");
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(worldKey)));
        world.getChunkAt(0,0);world.getChunkAt(1,0);
        sanitizer.recover(world,sessionId,Set.of(),Set.of(),error->{throw new CompletionException(error);});
        manual=new ManualScheduler();automatic=auto(manual);manual.ticks(512);
        for(int x:new int[]{2,4,6,15})require(inventory(x,2).isEmpty(),"Empty looted container stays empty after actual world reload and recovery x="+x);
        require(amount(inventory(8,2),Material.DIAMOND)==7,"Stored player items survive recovery");
        for(int[] at:new int[][]{{10,2},{11,6},{12,2},{12,6}})require(inventory(at[0],at[1]).isEmpty(),"Exclusion survives reload "+Arrays.toString(at));
        require(world.getChunkAt(0,0).getPersistentDataContainer().has(new NamespacedKey(plugin,"container_loot_decisions"),PersistentDataType.LONG_ARRAY),"Actual chunk decision ledger persisted");
        automatic.close();automatic=null;
        report.set("containers.initial",diagnostics);report.set("containers.checked",List.of("single chest","cross-chunk double chest","barrel","blue shulker box","player nonempty storage","player placed chest","dispensed shulker","airdrop-marked barrel","configured loot point"));
        report.set("containers.actual-world-reload-and-recovery-no-refill",true);report.set("containers.placement-limit","Constructed Bukkit events call the production handlers; no real player action was simulated.");
    }
    private PaperAutoContainerLoot auto(ManualScheduler scheduler){return new PaperAutoContainerLoot(plugin,world,sessionId,new Zone(16,8,32),sanitizer,new AutoContainerLootSettings(true,"basic",1,2,2,16),tables,List.of(new ContainerLootPoint("explicit",12,81,6,"basic")),new NativeLootItems(),new Random(11),scheduler);}
    private Block block(int x,int z,Material material){Block block=world.getBlockAt(x,81,z);block.setType(material,false);return block;}
    private void chest(Block block,org.bukkit.block.data.type.Chest.Type type){var data=(org.bukkit.block.data.type.Chest)block.getBlockData();data.setFacing(BlockFace.NORTH);data.setType(type);block.setBlockData(data,false);}
    private Inventory inventory(int x,int z){return ((Container)world.getBlockAt(x,81,z).getState()).getInventory();}
    private static int amount(Inventory inventory,Material material){return Arrays.stream(inventory.getContents()).filter(Objects::nonNull).filter(item->item.getType()==material).mapToInt(ItemStack::getAmount).sum();}
    private CompletableFuture<Void> airdrops(){
        for(int x=48;x<=96;x++)for(int z=48;z<=96;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        for(Chunk chunk:world.getLoadedChunks())sanitizer.ensure(chunk);
        var room=new RoomDefinition("rc5","RC5测试",1,2,1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(3));
        var map=new MapTemplate("fixture","RC5 Flat Fixture",world.getWorldPath(),new PlayableArea(-1000,1000,-1000,1000));
        var session=GameSession.waiting(sessionId,room,Instant.now());UUID winner=UUID.randomUUID();session.join(winner);session.prepare(map,new Random(3));
        Zone initial=new Zone(72,72,24);session.initialZone(initial);session.starting(new GameWorld(sessionId,room.id(),world.getName(),world.getWorldPath(),map));session.transition(GameState.RUNNING);
        var zone=new ZoneRuntime(initial,profile(),new Random(3),0);session.runningZone(zone,new ProtectionWindow(0,Duration.ZERO));
        var content=new MatchContent(Map.of(),tables,Map.of(),Map.of(),new AutoContainerLootSettings(false,"basic",1,2,2,16),new AirdropSettings(true,"basic",2,2,1,24,30));
        drops=new PaperAirdrops(plugin,session,sanitizer,content,ForkJoinPool.commonPool(),false);
        final long[] now={1_000_000_000L};
        return until(()->{
            zone.update(now[0]);drops.tick(zone,now[0]);now[0]+=100_000_000L;
            require(!drops.diagnostics().contains("FAILED"),"Airdrop tick healthy");return drops.diagnostics().contains("landed=1");
        },240,"First supply crate lands").thenCompose(unused->{
            List<Barrel> crates=crates();require(crates.size()==1,"Exactly one actual supply barrel");Barrel crate=crates.getFirst();
            require(crate.getY()==81&&crate.getBlock().getRelative(BlockFace.DOWN).getType()==Material.STONE,"Safe solid landing on test platform");
            require(zone.current().contains(crate.getX()+.5,crate.getZ()+.5)&&zone.next().contains(crate.getX()+.5,crate.getZ()+.5),"Landing inside current and destination zones");
            require(amount(crate.getInventory(),Material.BREAD)==6,"Actual native supply contents");require(crate.customName()!=null&&plain(crate.customName()).contains("第 1 轮补给空投"),"Chinese supply crate label");
            for(int i=0;i<20;i++)drops.tick(zone,now[0]);require(crates().size()==1&&drops.diagnostics().contains("landed=1"),"Same shrinking stage does not duplicate crate");
            require(tickets()==0&&visuals()==0,"Landed crate releases display and chunk ticket");
            try{require(!AirdropLedger.claim(world.getWorldPath(),sessionId,0),"Persisted claim rejects a duplicate stage");}catch(Exception error){throw new CompletionException(error);}
            report.set("airdrop.location",List.of(crate.getX(),crate.getY(),crate.getZ()));report.set("airdrop.stage-zero-contents",6);
            now[0]=62_000_000_000L;
            return until(()->{zone.update(now[0]);drops.tick(zone,now[0]);now[0]+=50_000_000L;require(!drops.diagnostics().contains("FAILED"),"Second-round descent healthy");return visuals()>0;},160,"Second round creates falling display for cancellation check");
        }).thenCompose(unused->drops.stop()).thenCompose(unused->{
            require(visuals()==0&&tickets()==0,"Stopping during descent removes display and chunk ticket");require(crates().size()==1,"Cancelled second drop created no extra barrel");
            drops=new PaperAirdrops(plugin,session,sanitizer,content,ForkJoinPool.commonPool(),true);
            for(int i=0;i<20;i++)drops.tick(zone,now[0]);require(visuals()==0&&crates().size()==1,"Recovered shrinking stage is not replayed");
            return drops.stop();
        }).thenRun(()->{
            try{String ledger=Files.readString(world.getWorldPath().resolve("battleroyale-airdrops.ledger"));require(ledger.equals(sessionId+"\n1\n"),"Both attempted stages persisted atomically");report.set("airdrop.ledger",ledger);}catch(Exception error){throw new CompletionException(error);}
            report.set("airdrop.real-landed-count",1);report.set("airdrop.cancelled-in-flight-cleanup",true);report.set("airdrop.recovery-does-not-replay",true);ending(session,winner);
        });
    }
    private void ending(GameSession session,UUID winner){
        session.outcome(new MatchOutcome(Set.of(winner),false,"RC5_PROBE",0,1));var pending=new CompletableFuture<Void>();
        boolean blocked=false;try{EndingReturnPolicy.require(session,winner,pending);}catch(IllegalStateException expected){blocked=true;}require(blocked,"Early return waits for durable result");pending.complete(null);EndingReturnPolicy.require(session,winner,pending);
        var scheduler=new ManualScheduler();long[] now={0};int[] completions={0};var shown=new ArrayList<Integer>();
        var effects=new CelebrationEffects(plugin);
        new WinnerShowcase(scheduler,()->now[0],Duration.ofSeconds(3),()->effects.title(session,id->"RC5测试玩家"),()->{},seconds->{shown.add(seconds);effects.returnCountdown(session,seconds);},()->completions[0]++,error->{throw new CompletionException(error);});
        for(int i=1;i<=3;i++){now[0]=i*1_000_000_000L;scheduler.ticks(1);}scheduler.ticks(3);
        require(shown.equals(List.of(3,2,1,0))&&completions[0]==1,"One ending countdown from remaining deadline");
        require(plain(CelebrationEffects.returnStatus(3)).contains("3 秒")&&plain(CelebrationEffects.returnStatus(3)).contains("/br leave"),"Chinese countdown and early-return command component");
        report.set("ending.seconds",shown);report.set("ending.actionbar",plain(CelebrationEffects.returnStatus(3)));report.set("ending.result-durability-gate",true);
    }
    private ZoneProfile profile(){return new ZoneProfile("probe",List.of(new ZoneProfile.InitialSize(2,500)),List.of(new ZoneProfile.Stage(Duration.ofSeconds(1),Duration.ofSeconds(60),12,0,0,0),new ZoneProfile.Stage(Duration.ofSeconds(1),Duration.ofSeconds(60),6,0,0,0)));}
    private List<Barrel> crates(){var result=new ArrayList<Barrel>();for(Chunk chunk:world.getLoadedChunks())for(BlockState state:chunk.getTileEntities(false))if(state instanceof Barrel barrel&&Optional.ofNullable(barrel.getPersistentDataContainer().get(new NamespacedKey(plugin,"airdrop"),PersistentDataType.STRING)).orElse("").startsWith(sessionId+":"))result.add(barrel);return result;}
    private long visuals(){return world.getEntitiesByClass(BlockDisplay.class).stream().filter(display->display.getPersistentDataContainer().has(new NamespacedKey(plugin,"airdrop"))).count();}
    private int tickets(){return world.getPluginChunkTickets().getOrDefault(plugin,List.of()).size();}
    private CompletableFuture<Void> until(BooleanSupplier condition,int ticks,String description){
        var future=new CompletableFuture<Void>();new BukkitRunnable(){int elapsed;public void run(){try{if(condition.getAsBoolean()){cancel();future.complete(null);}else if(++elapsed>=ticks)throw new IllegalStateException("Timed out: "+description);}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;
    }
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static String plain(Component component){return PlainTextComponentSerializer.plainText().serialize(component);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("RC5 assertion: "+message);}
    private static final class ManualScheduler implements GameScheduler {
        private final List<Job> jobs=new ArrayList<>();
        private static final class Job{final Runnable action;boolean cancelled;Job(Runnable action){this.action=action;}}
        public Task repeat(int ticks,Runnable action){Job job=new Job(action);jobs.add(job);return ()->job.cancelled=true;}
        void ticks(int count){for(int i=0;i<count;i++)for(Job job:List.copyOf(jobs))if(!job.cancelled)job.action.run();}
    }
}
