package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.combat.ProtectionWindow;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Isolated native horse save/reload and actual celebration explosion/damage-listener verification. */
public final class Rc7HorseCelebrationProbe implements Listener {
    private final JavaPlugin plugin;private final YamlConfiguration report=new YamlConfiguration();
    private World world;private NamespacedKey worldKey;private WorldSanitizer sanitizer;private PaperMatchHorses horses;
    private GameSession session;private ZoneRuntime zone;private UUID natural,kept,removed,winner;private Cow observer;
    private CelebrationEffects effects;private boolean busy;private int fireworks,explosions,cancelledExplosions,damageEvents;private Throwable eventFailure;
    private final Set<Chunk> forcedChunks=new HashSet<>();
    private final HorseSettings settings=new HorseSettings(true,2,5,128,8);
    public Rc7HorseCelebrationProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc7"),"Explicit isolated rc7 flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real players in isolated fixture");require(!busy,"Already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc7horses all");
        busy=true;CompletableFuture<Void> work;
        try{setup();work=await(()->owned().size()==1,()->horses.tick(zone,0),300)
                .thenCompose(unused->{firstHorse();return await(()->owned().size()==2,()->horses.tick(zone,5_000_000_000L),300);})
                .thenCompose(unused->restore()).thenCompose(unused->celebrate());}
        catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            var drained=horses==null?CompletableFuture.<Void>completedFuture(null):horses.stop();
            drained.whenComplete((ignored,stopError)->main(()->{
                Throwable failure=error==null?stopError:error;
                try{
                    if(effects!=null&&session!=null)effects.close(session.sessionId());HandlerList.unregisterAll(this);
                    releaseFixtureChunks();
                    if(world!=null){require(tickets()==0,"No horse chunk tickets remain");if(sanitizer!=null)sanitizer.remove(world.getUID());require(plugin.getServer().unloadWorld(world,true),"Fixture unloads");}
                    report.set("status",failure==null?"passed":"failed");if(failure!=null)report.set("failure",failure.toString());
                    report.set("limits",List.of("Dedicated generated local world; no production map changed.","Horse timers are advanced by the probe; real entity save/reload, saddle and tame APIs are exercised.","Actual Firework explosions use the production CelebrationEffects and CombatListener against a native cow.","No player riding, client fireworks rendering or complete real-player match is asserted."));
                    Path file=plugin.getDataFolder().toPath().resolve("rc7/horse-celebration-report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
                    if(failure!=null)throw new CompletionException(failure);
                    sender.sendMessage("RC7 HORSES CELEBRATION SUCCESS horses=OK recovery=OK fireworks=OK no-damage=OK cleanup=OK path="+file.toAbsolutePath());
                }catch(Throwable failed){sender.sendMessage("RC7 HORSES CELEBRATION FAILED "+failed);plugin.getLogger().log(Level.SEVERE,"RC7 HORSES CELEBRATION FAILED",failed);}
                finally{busy=false;}
            }));
        }));
    }
    private void setup()throws ReflectiveOperationException {
        worldKey=new NamespacedKey("battleroyale_probe","rc7_horses_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(worldKey).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);world.setSpawnLocation(32,82,32);
        for(int x=0;x<=64;x++)for(int z=0;z<=64;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        keepFixtureChunks();
        session=match(UUID.randomUUID());zone=session.zone().orElseThrow();
        sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot"));sanitizer.register(world,session.sessionId(),error->{throw new CompletionException(error);});
        require(PaperMatchHorses.safe(world,6,81,6),"Flat wide footprint accepted");world.getBlockAt(6,81,6).setType(Material.WATER,false);
        require(!PaperMatchHorses.safe(world,6,81,6),"Liquid rejected");world.getBlockAt(6,81,6).setType(Material.AIR,false);
        world.getBlockAt(7,82,6).setType(Material.STONE,false);require(!PaperMatchHorses.safe(world,6,81,6),"Side/head clearance checked");world.getBlockAt(7,82,6).setType(Material.AIR,false);
        natural=world.spawn(new Location(world,2.5,81,2.5),Horse.class,CreatureSpawnEvent.SpawnReason.NATURAL,horse->{horse.setAI(false);horse.setPersistent(true);horse.setRemoveWhenFarAway(false);}).getUniqueId();
        horses=new PaperMatchHorses(plugin,session,sanitizer,settings,false);
        // Access the plugin-owned instance so the existing production damage listener protects these fireworks.
        var main=Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale"));var field=main.getClass().getDeclaredField("runtime");field.setAccessible(true);
        effects=((PluginRuntime)field.get(main)).celebrations();plugin.getServer().getPluginManager().registerEvents(this,plugin);
        report.set("platform",plugin.getServer().getVersion());report.set("world",world.getName());
    }
    private GameSession match(UUID id){
        var room=new RoomDefinition("rc7","RC7骑乘测试",1,2,1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(1));
        var map=new MapTemplate("fixture","RC7 Flat Fixture",world.getWorldPath(),new PlayableArea(-1000,1000,-1000,1000));
        var value=GameSession.waiting(id,room,Instant.now());winner=UUID.randomUUID();value.join(winner);value.prepare(map,new Random(3));
        Zone initial=new Zone(32,32,24);value.initialZone(initial);value.starting(new GameWorld(id,room.id(),world.getName(),world.getWorldPath(),map));value.transition(GameState.RUNNING);
        var profile=new ZoneProfile("probe",List.of(new ZoneProfile.InitialSize(2,500)),List.of(new ZoneProfile.Stage(Duration.ofSeconds(600),Duration.ofSeconds(600),12,0,0,0)));
        value.runningZone(new ZoneRuntime(initial,profile,new Random(3),0),new ProtectionWindow(0,Duration.ZERO));return value;
    }
    private List<Horse> owned(){return world.getEntitiesByClass(Horse.class).stream().filter(horse->session.sessionId().toString().equals(horse.getPersistentDataContainer().get(new NamespacedKey(plugin,PaperMatchHorses.SESSION_KEY),PersistentDataType.STRING))).toList();}
    private void firstHorse(){
        var horse=owned().getFirst();kept=horse.getUniqueId();horse.setAI(false);
        require(horse.isAdult()&&horse.isTamed()&&horse.getOwner()==null,"Adult tamed horse needs no assigned owner");
        require(horse.getInventory().getSaddle()!=null&&horse.getInventory().getSaddle().getType()==Material.SADDLE,"Native saddle equipped");
        for(int i=0;i<100;i++)horses.tick(zone,4_999_999_999L);require(owned().size()==1,"Refresh cooldown enforced");
        report.set("horses.observed.natural-chunk-loaded",world.isChunkLoaded(0,0));report.set("horses.observed.natural-chunk-forced",world.isChunkForceLoaded(0,0));
        require(plugin.getServer().getEntity(natural)!=null,"Unowned natural horse preserved: chunkLoaded="+world.isChunkLoaded(0,0)+", forced="+world.isChunkForceLoaded(0,0));
    }
    private CompletableFuture<Void> restore(){
        var values=owned();require(values.size()==2,"Session cap reached");for(var horse:values)horse.setAI(false);
        long[] encoded=world.getPersistentDataContainer().get(new NamespacedKey(plugin,PaperMatchHorses.LEDGER_KEY),PersistentDataType.LONG_ARRAY);
        require(encoded!=null&&encoded.length==4&&HorseSpawnPolicy.fromEncoded(settings,encoded).count()==2,"Native LONG_ARRAY ledger decodes both permanent claims");
        var first=(Horse)Objects.requireNonNull(plugin.getServer().getEntity(kept));var other=values.stream().filter(horse->!horse.getUniqueId().equals(kept)).findFirst().orElseThrow();removed=other.getUniqueId();
        require(first.getLocation().distanceSquared(other.getLocation())>=64,"Horse spawns spread apart");first.getInventory().setSaddle(null);other.remove();
        return horses.stop().thenRun(()->{
            sanitizer.remove(world.getUID());require(tickets()==0,"Stop releases tickets");releaseFixtureChunks();require(plugin.getServer().unloadWorld(world,true),"Horse world saved and unloaded");
            world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(worldKey)));
            require(Arrays.equals(encoded,world.getPersistentDataContainer().get(new NamespacedKey(plugin,PaperMatchHorses.LEDGER_KEY),PersistentDataType.LONG_ARRAY)),"Encoded coordinate bits survive native world save/reload exactly");
            keepFixtureChunks();
            sanitizer.recover(world,session.sessionId(),Set.of(),Set.of(),error->{throw new CompletionException(error);});
            horses=new PaperMatchHorses(plugin,session,sanitizer,settings,true);for(int i=0;i<200;i++)horses.tick(zone,100_000_000_000L);
            require(owned().size()==1&&plugin.getServer().getEntity(removed)==null,"Deleted mount never replenishes after real world reload");
            var retained=(Horse)Objects.requireNonNull(plugin.getServer().getEntity(kept));require(retained.getInventory().getSaddle()==null,"Taken saddle never restored");
            require(retained.isTamed()&&retained.isAdult()&&plugin.getServer().getEntity(natural)!=null,"Native horse state and unowned natural horse persist");
            require(tickets()==0,"No unnecessary chunks pinned after cap");report.set("horses.saved-reloaded-and-no-refill",true);report.set("horses.saddle-tame-spread-cooldown",true);
            // A legacy/recovered session without a matching ledger fails closed instead of backfilling mounts.
            UUID originalWinner=winner;var unknown=match(UUID.randomUUID());winner=originalWinner;var denied=new PaperMatchHorses(plugin,unknown,sanitizer,settings,true);
            denied.tick(unknown.zone().orElseThrow(),0);require(denied.diagnostics().contains("RECOVERY_OR_ERROR")&&owned().size()==1,"Unknown recovery ledger fails closed");denied.close();
        });
    }
    private CompletableFuture<Void> celebrate(){
        session.outcome(new MatchOutcome(Set.of(winner),false,"RC7_PROBE",0,1));
        horses.tick(zone,200_000_000_000L);require(owned().size()==1,"Ending session never generates mounts");
        // No players remain in this isolated world after the save/reload test. Loaded chunks alone
        // do not promise entity ticks; own temporary force-loads and let the engine promote them.
        var spawn=world.getSpawnLocation();int centerX=spawn.getBlockX()>>4,centerZ=spawn.getBlockZ()>>4;
        for(int x=centerX-1;x<=centerX+1;x++)for(int z=centerZ-1;z<=centerZ+1;z++){
            var chunk=world.getChunkAt(x,z);if(!chunk.isForceLoaded()){chunk.setForceLoaded(true);forcedChunks.add(chunk);}
        }
        return delay(3).thenCompose(unused->celebrateTicking());
    }
    private CompletableFuture<Void> celebrateTicking(){
        effects.fire(session);var first=world.getEntitiesByClass(Firework.class).stream().filter(effects::marked).findFirst().orElseThrow();
        observer=world.spawn(first.getLocation(),Cow.class,cow->{cow.setAI(false);cow.setGravity(false);});double health=observer.getHealth();first.detonate();
        return delay(60).thenCompose(unused->{
            report.set("celebration.observed.spawned",fireworks);report.set("celebration.observed.explosions",explosions);report.set("celebration.observed.cancelled-explosions",cancelledExplosions);
            report.set("celebration.observed.damage-events",damageEvents);report.set("celebration.observed.health-before",health);report.set("celebration.observed.health-after",observer.getHealth());
            report.set("celebration.observed.observer-valid",observer.isValid());report.set("celebration.observed.first-firework-valid",first.isValid());report.set("celebration.observed.first-firework-ticks",first.getTicksLived());
            require(eventFailure==null,"Firework metadata event checks: "+eventFailure);require(fireworks==6,"Three waves of two rockets for one winner");
            require(explosions>0&&cancelledExplosions==0,"Native explosion events occur: explosions="+explosions+", cancelled="+cancelledExplosions+", firstTicks="+first.getTicksLived());
            require(damageEvents>0&&observer.isValid()&&observer.getHealth()==health,"Real explosion damage cancelled by production listener: events="+damageEvents+", health="+health+"->"+observer.getHealth()+", valid="+observer.isValid());
            effects.close(session.sessionId());require(world.getEntitiesByClass(Firework.class).isEmpty(),"All live fireworks removed");
            effects.fire(session);int beforeClose=fireworks;effects.close(session.sessionId());
            return delay(25).thenRun(()->{require(fireworks==beforeClose&&world.getEntitiesByClass(Firework.class).isEmpty(),"Close cancels pending waves without late spawns");report.set("celebration.native-three-waves",true);report.set("celebration.actual-damage-events-cancelled",damageEvents);report.set("celebration.pending-waves-cancelled",true);});
        });
    }
    @EventHandler public void spawned(EntitySpawnEvent event){
        if(!(event.getEntity() instanceof Firework firework)||session==null||!session.sessionId().toString().equals(firework.getPersistentDataContainer().get(new NamespacedKey("battleroyale","celebration_session"),PersistentDataType.STRING)))return;
        fireworks++;try{var meta=firework.getFireworkMeta();require(!firework.isPersistent()&&meta.getEffectsSize()==2,"Bounded ephemeral multi-effect rocket");require(meta.getEffects().getFirst().getType()==FireworkEffect.Type.BALL_LARGE&&meta.getEffects().getFirst().getColors().size()>=3,"Large multicolor primary burst");}catch(Throwable failure){eventFailure=failure;}
    }
    @EventHandler(priority=EventPriority.MONITOR)public void damage(EntityDamageByEntityEvent event){if(observer!=null&&event.getEntity().getUniqueId().equals(observer.getUniqueId())&&effects.marked(event.getDamager())){damageEvents++;if(!event.isCancelled())eventFailure=new IllegalStateException("Celebration damage was not cancelled");}}
    @EventHandler(priority=EventPriority.MONITOR)public void exploded(FireworkExplodeEvent event){if(effects!=null&&effects.marked(event.getEntity())){explosions++;if(event.isCancelled())cancelledExplosions++;}}
    private void keepFixtureChunks(){for(int x=0;x<4;x++)for(int z=0;z<4;z++){var chunk=world.getChunkAt(x,z);if(!chunk.isForceLoaded()){chunk.setForceLoaded(true);forcedChunks.add(chunk);}chunk.getEntities();}}
    private void releaseFixtureChunks(){for(var chunk:forcedChunks)chunk.setForceLoaded(false);forcedChunks.clear();}
    private CompletableFuture<Void> delay(int ticks){var result=new CompletableFuture<Void>();plugin.getServer().getScheduler().runTaskLater(plugin,()->result.complete(null),ticks);return result;}
    private CompletableFuture<Void> await(BooleanSupplier done,Runnable tick,int maximum){var result=new CompletableFuture<Void>();new BukkitRunnable(){int attempts;@Override public void run(){try{tick.run();if(done.getAsBoolean()){cancel();result.complete(null);}else if(++attempts>maximum)throw new IllegalStateException("Horse probe timeout: "+horses.diagnostics());}catch(Throwable failure){cancel();result.completeExceptionally(failure);}}}.runTaskTimer(plugin,1,1);return result;}
    private int tickets(){return world.getPluginChunkTickets().entrySet().stream().filter(entry->entry.getKey().equals(plugin)).mapToInt(entry->entry.getValue().size()).sum();}
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
