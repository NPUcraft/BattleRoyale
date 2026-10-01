package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.zone.Zone;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Isolated native chunk/PDC/reload/event/loot checks. No connected-client or multiplayer claim. */
public final class Rc9LootProbe {
    private final JavaPlugin plugin;private boolean busy;private World world;private NamespacedKey worldKey;
    private final UUID sessionId=UUID.randomUUID();private WorldSanitizer sanitizer;private PaperAirdropBeacons beacon;
    private PaperLootRuntime loot;private ExecutorService io;private final YamlConfiguration report=new YamlConfiguration();
    public Rc9LootProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc9"),"Explicit isolated rc9 flag");require(!busy&&plugin.getServer().getOnlinePlayers().isEmpty(),"No active probe or native players");
        require(args.length==2&&args[1].equals("all"),"p26rc9loot all");busy=true;io=Executors.newSingleThreadExecutor();CompletableFuture<Void> work;
        try{setupAndReload();work=beaconAndDrain().thenCompose(unused->ground());}catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            Throwable failure=error;
            try{
                if(loot!=null)loot.close();if(beacon!=null)beacon.close();if(sanitizer!=null)sanitizer.close();io.shutdown();
                if(world!=null){for(var chunk:List.copyOf(world.getPluginChunkTickets().getOrDefault(plugin,List.of())))chunk.removePluginChunkTicket(plugin);
                    require(plugin.getServer().unloadWorld(world,true),"Native fixture unloaded");}
            }catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            try{
                report.set("status",failure==null?"passed":"failed");report.set("platform",plugin.getServer().getVersion());
                report.set("limits",List.of("Dedicated generated local world; real block/PDC save and unload/reload, native beacon and item entities.","Block break/place/explosion/piston handlers are invoked with real event objects and a Player interface recorder; no client input is claimed.","Sanitation ticks are explicitly driven; actual ground loot uses the production scheduled runtime and real elapsed time."));
                var path=plugin.getDataFolder().toPath().resolve("rc9/loot-report.yml");AtomicFiles.write(path,report.saveToString().getBytes(StandardCharsets.UTF_8));
                if(failure!=null)throw new CompletionException(failure);sender.sendMessage("RC9 LOOT SUCCESS native-blocks=OK ores=preserved player-blocks=preserved ground=bounded path="+path.toAbsolutePath());
            }catch(Throwable failed){sender.sendMessage("RC9 LOOT FAILED "+failed);plugin.getLogger().log(Level.SEVERE,"RC9 LOOT FAILED",failed);}finally{busy=false;}
        }));
    }
    private void setupAndReload(){
        worldKey=new NamespacedKey("battleroyale_probe","rc9_loot_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(worldKey).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
        Chunk chunk=world.getChunkAt(4,4);chunk.addPluginChunkTicket(plugin);int low=world.getMinHeight();
        for(int x=64;x<80;x++)for(int z=64;z<80;z++)for(int y=low;y<low+2;y++)world.getBlockAt(x,y,z).setType(Material.DIAMOND_BLOCK,false);
        for(int x=64;x<80;x++)for(int z=64;z<80;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        world.getBlockAt(68,85,68).setType(Material.DIAMOND_ORE,false);world.getBlockAt(69,85,68).setType(Material.DEEPSLATE_DIAMOND_ORE,false);
        world.getBlockAt(70,85,68).setType(Material.ANCIENT_DEBRIS,false);
        world.getBlockAt(68,95,68).setType(Material.IRON_BLOCK,false);world.getBlockAt(69,95,68).setType(Material.GOLD_BLOCK,false);world.getBlockAt(70,95,68).setType(Material.NETHERITE_BLOCK,false);
        sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot_session"));sanitizer.register(world,sessionId,error->{throw new CompletionException(error);});
        Player viewer=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->{
            if(method.getName().equals("getUniqueId"))return new UUID(0,9);if(method.getName().equals("getWorld"))return world;if(method.getName().equals("toString"))return "Rc9LootPlayerRecorder";return null;
        });
        Block placed=world.getBlockAt(66,81,66);var original=placed.getState();placed.setType(Material.DIAMOND_BLOCK,false);
        sanitizer.placed(new BlockPlaceEvent(placed,original,placed.getRelative(BlockFace.DOWN),new ItemStack(Material.DIAMOND_BLOCK),viewer,true,EquipmentSlot.HAND));
        Block generated=world.getBlockAt(67,81,66);WorldSanitizer.preserveGenerated(plugin,generated);generated.setType(Material.IRON_BLOCK,false);
        var broken=new BlockBreakEvent(world.getBlockAt(68,95,68),viewer);sanitizer.nativeBreak(broken);
        require(broken.isCancelled()&&!broken.isDropItems()&&broken.getBlock().getType()==Material.STONE,"Native resources cannot be mined before their scan turn");
        var moved=new BlockPistonExtendEvent(world.getBlockAt(71,95,68),List.of(world.getBlockAt(70,95,68)),BlockFace.EAST);sanitizer.nativePiston(moved);require(moved.isCancelled(),"Unprocessed native resources cannot be moved by piston");
        var allowed=new BlockPistonExtendEvent(world.getBlockAt(65,81,66),List.of(placed),BlockFace.EAST);sanitizer.nativePiston(allowed);require(!allowed.isCancelled(),"Player-placed resource blocks are not blocked by native policy");
        var cause=world.spawn(new Location(world,72,100,72),ArmorStand.class);var explosion=new EntityExplodeEvent(cause,cause.getLocation(),new ArrayList<>(List.of(world.getBlockAt(69,95,68),placed)),1,ExplosionResult.DESTROY);
        sanitizer.nativeExplosion(explosion);cause.remove();require(explosion.blockList().equals(List.of(placed))&&world.getBlockAt(69,95,68).getType()==Material.STONE,"Explosion list excludes only native resource blocks");
        sanitizer.tick();long changed=0;for(int x=64;x<80;x++)for(int z=64;z<80;z++)for(int y=low;y<low+2;y++)if(world.getBlockAt(x,y,z).getType()==Material.DEEPSLATE)changed++;
        require(changed==256,"Exactly 256 native writes at most in first scan tick");int cursor=sanitizer.nativeCursor(chunk);require(cursor==256,"Durable cursor records only applied first-tick work");
        Set<Long> blocks=sanitizer.blockKeys(world.getUID()),entities=sanitizer.entityKeys(world.getUID());UUID worldId=world.getUID();world.save();
        sanitizer.close();chunk.removePluginChunkTicket(plugin);require(plugin.getServer().unloadWorld(world,true),"Mid-scan world unloads without sanitation pinning");
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(worldKey)));require(world.getUID().equals(worldId),"Actual save reload preserves world identity");
        chunk=world.getChunkAt(4,4);chunk.addPluginChunkTicket(plugin);sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot_session"));
        sanitizer.recover(world,sessionId,blocks,entities,error->{throw new CompletionException(error);});require(sanitizer.nativeCursor(chunk)==cursor,"Recovery resumes saved cursor rather than rescanning player blocks");
        report.set("native.first-tick-writes",changed);report.set("native.mid-scan-save-reload-cursor",cursor);report.set("native.early-resource-events",true);
    }
    private CompletableFuture<Void> beaconAndDrain(){
        beacon=new PaperAirdropBeacons(plugin,world,sessionId,io);
        return until(beacon::recover,240,"Beacon recovery ready").thenCompose(unused->{
            var plan=beacon.plan(0,72,81,72).orElseThrow();return beacon.persist(plan);
        }).thenCompose(plan->onMain(()->{require(beacon.install(plan),"Actual native beacon installs during pending native block scan");return plan;})).thenCompose(plan->
            until(()->{sanitizer.tick();return sanitizer.nativeCursor(world.getChunkAt(4,4))==(world.getMaxHeight()-world.getMinHeight())*256
                &&((Beacon)world.getBlockAt(plan.x(),plan.y(),plan.z()).getState()).getTier()==1;},240,"Bounded native scan and beacon activation")
                .thenRun(()->{
                    for(var cell:plan.cells())require(world.getBlockAt(cell.x(),cell.y(),cell.z()).getBlockData().getAsString().equals(cell.placed()),"Generated beacon cell survives native scan");
                    require(world.getBlockAt(66,81,66).getType()==Material.DIAMOND_BLOCK&&world.getBlockAt(67,81,66).getType()==Material.IRON_BLOCK,"Player/generated exclusions survive actual world reload and complete scan");
                    require(world.getBlockAt(68,85,68).getType()==Material.DIAMOND_ORE&&world.getBlockAt(69,85,68).getType()==Material.DEEPSLATE_DIAMOND_ORE&&world.getBlockAt(70,85,68).getType()==Material.ANCIENT_DEBRIS,"Natural ores and ancient debris remain untouched");
                    require(world.getBlockAt(70,95,68).getType()==Material.STONE,"Deferred original netherite block becomes ordinary stone");
                    world.getBlockAt(71,95,68).setType(Material.EMERALD_BLOCK,false);sanitizer.ensure(world.getChunkAt(4,4));sanitizer.tick();require(world.getBlockAt(71,95,68).getType()==Material.EMERALD_BLOCK,"Completed chunk never rescans later placed blocks");
                    beacon.close();beacon=null;report.set("native.ores-preserved",true);report.set("native.player-and-generated-preserved",true);report.set("native.actual-beacon-tier1-survived",true);
                }));
    }
    private CompletableFuture<Void> ground(){
        var room=new RoomDefinition("rc9","RC9 local loot",1,2,1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(1));
        var map=new MapTemplate("fixture","Local flat loot fixture",world.getWorldPath(),new PlayableArea(-1000,1000,-1000,1000));
        var session=GameSession.waiting(sessionId,room,Instant.now());session.join(UUID.randomUUID());session.prepare(map,new Random(9));session.initialZone(new Zone(72,72,64));session.starting(new GameWorld(sessionId,room.id(),world.getName(),world.getWorldPath(),map));
        var ground=new LootArea("ground",8,135,-64,319,8,135,"basic",1,400,400,10);
        var table=new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",1,1,1)));
        var content=new MatchContent(Map.of(),Map.of("basic",table),Map.of("fixture",new MapLoot(List.of(),List.of(ground))),Map.of(),new AutoContainerLootSettings(false,"basic",.4,1,3,16),new AirdropSettings(false,"basic",2,2,8,24,120));
        loot=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new Random(9),new NamespacedKey(plugin,"ground_loot_session"),new PaperScheduler(plugin),io);
        // Establish the exact loaded-chunk benchmark separately from terrain generation latency.
        for(int x=0;x<=8;x++)for(int z=0;z<=8;z++)world.getChunkAt(x,z).addPluginChunkTicket(plugin);
        long started=System.nanoTime();var generated=loot.generate();
        return until(generated::isDone,1800,"Production ground runtime finishes within preparation budget").thenRun(()->{
            generated.join();require(loot.progressCompleted()==400&&loot.progressTotal()==400,"Completed preparation reports all four hundred resolved candidate points");double elapsed=(System.nanoTime()-started)/1_000_000_000.0;require(elapsed<20,"Four hundred points on loaded native chunks prepare within twenty seconds");
            // Controlled sample chunks are reloaded before counting persistent item entities; production does not pin them.
            long drops=0;var positions=new HashSet<String>();for(int x=0;x<=8;x++)for(int z=0;z<=8;z++)for(var entity:world.getChunkAt(x,z).getEntities())if(entity instanceof Item item&&sessionId.toString().equals(item.getPersistentDataContainer().get(new NamespacedKey(plugin,"ground_loot_session"),org.bukkit.persistence.PersistentDataType.STRING))){drops++;positions.add(item.getLocation().getBlockX()+":"+item.getLocation().getBlockZ());}
            require(drops==0,"Unopened field supplies contain no item entities");
            try{var saved=new GroundSupplyLedger(world.getWorldPath(),sessionId,world.getUID()).read().orElseThrow();
                for(var point:saved.points())positions.add(point.x()+":"+point.z());
                require(saved.points().size()==400,"All four hundred safe fixture points are durably sealed");
                require(saved.points().size()==positions.size(),"Stratified points do not stack on the same column");
            }catch(java.io.IOException error){throw new CompletionException(error);}
            var restored=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new Random(9),new NamespacedKey(plugin,"ground_loot_session"),new PaperScheduler(plugin),io);restored.recoverAutomatic();require(restored.state()==PaperLootRuntime.State.COMPLETE,"Recovery skips static ground generation");restored.close();
            report.set("ground.requested",400);report.set("ground.actual-item-entities",drops);report.set("ground.distinct-columns",positions.size());report.set("ground.real-elapsed-seconds",elapsed);report.set("ground.benchmark","400 safe points on 81 explicitly loaded native chunks; under 20 seconds");report.set("ground.diagnostics",loot.diagnostics());
            report.set("container.defaults.chance",AutoContainerLootSettings.DEFAULT.chance());report.set("container.defaults.min-rolls",AutoContainerLootSettings.DEFAULT.minRolls());report.set("container.defaults.max-rolls",AutoContainerLootSettings.DEFAULT.maxRolls());
        }).thenCompose(unused->stopDuringRequests(room));
    }
    private CompletableFuture<Void> stopDuringRequests(RoomDefinition room){
        loot.close();byte[] original;
        try{original=java.nio.file.Files.readAllBytes(world.getWorldPath().resolve(GroundSupplyLedger.FILE));}catch(java.io.IOException error){return CompletableFuture.failedFuture(error);}
        var map=new MapTemplate("fixture","Local cancellation fixture",world.getWorldPath(),new PlayableArea(4000,4400,4000,4400));
        UUID cancelId=UUID.randomUUID();var session=GameSession.waiting(cancelId,room,Instant.now());session.join(UUID.randomUUID());session.prepare(map,new Random(11));
        session.initialZone(new Zone(4160,4160,64));session.starting(new GameWorld(cancelId,room.id(),world.getName(),world.getWorldPath(),map));
        var area=new LootArea("cancel",4096,4223,-64,319,4096,4223,"basic",1,400,400,10);
        var table=new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",1,1,1)));
        var content=new MatchContent(Map.of(),Map.of("basic",table),Map.of("fixture",new MapLoot(List.of(),List.of(area))),Map.of(),new AutoContainerLootSettings(false,"basic",0,1,1,16),new AirdropSettings(false,"basic",1,1,8,24,120));
        Runnable[] action=new Runnable[1];boolean[] scheduled={true};
        com.npucraft.battleroyale.service.GameScheduler manual=(period,task)->{action[0]=task;return ()->scheduled[0]=false;};
        loot=new PaperLootRuntime(plugin,session,sanitizer,content,new NativeLootItems(),new Random(11),new NamespacedKey(plugin,"ground_loot_session"),manual,io);
        var generated=loot.generate();action[0].run();String before=loot.diagnostics();
        require(before.matches(".*max-in-flight=[1-8] .*"),"Production runtime issued a bounded window of native asynchronous chunk requests");
        var stopped=loot.stop();
        return until(()->{if(scheduled[0])action[0].run();return stopped.isDone();},600,"Outstanding native chunk requests drain after stop").thenRun(()->{
            stopped.join();require(generated.isCompletedExceptionally(),"Stopped preparation cannot complete successfully");
            require(loot.diagnostics().contains("ground-points=0 "),"Stopped candidates never inspect or plan points");
            try{require(Arrays.equals(original,java.nio.file.Files.readAllBytes(world.getWorldPath().resolve(GroundSupplyLedger.FILE))),"Stop does not reseal or overwrite the previously committed plan");}catch(java.io.IOException error){throw new CompletionException(error);}
            report.set("ground.stop-native-requests",before);report.set("ground.stop-no-late-plan-or-reseal",true);
        });
    }
    private CompletableFuture<Void> until(BooleanSupplier condition,int maximum,String description){var future=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();future.complete(null);}else if(++ticks>=maximum)throw new IllegalStateException("Timed out: "+description);}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;}
    private <T>CompletableFuture<T> onMain(Callable<T> action){var result=new CompletableFuture<T>();main(()->{try{result.complete(action.call());}catch(Throwable error){result.completeExceptionally(error);}});return result;}
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static void require(boolean value,String description){if(!value)throw new IllegalStateException("RC9 loot assertion: "+description);}
}
