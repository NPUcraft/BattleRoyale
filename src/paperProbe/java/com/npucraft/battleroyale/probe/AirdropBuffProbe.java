package com.npucraft.battleroyale.probe;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.paper.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

/** Real tier-one beacon plus the production native-event filter. Player is a declared interface recorder. */
public final class AirdropBuffProbe {
    private final JavaPlugin plugin;
    private boolean busy;private World world;private PaperAirdropBeacons helper;private ExecutorService io;
    public AirdropBuffProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.buff"),"Explicit isolated buff flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty()&&!busy,"No real players or running probe");
        require(args.length==2&&args[1].equals("all"),"p26beaconbuff all");busy=true;io=Executors.newSingleThreadExecutor();
        var report=new YamlConfiguration();var state=new Viewer();
        CompletableFuture<Void> work;
        try{
            var key=new NamespacedKey("battleroyale_probe","buff_"+UUID.randomUUID().toString().replace("-",""));
            world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
            world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
            for(int x=65;x<=79;x++)for(int z=65;z<=79;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
            helper=new PaperAirdropBeacons(plugin,world,UUID.randomUUID(),io,player->state.participant);
            Block ordinary=world.getBlockAt(66,83,66);ordinary.setType(Material.BEACON,false);
            var recoveryPulse=event(ordinary,state.player(),true);helper.effect(recoveryPulse);
            require(recoveryPulse.isCancelled(),"Owned world cannot leak restored beacon effects before recovery completes");
            report.set("checks.recovery-window-closed",true);
            work=until(helper::recover,240,"Beacon ledger ready").thenCompose(unused->{
                var ordinaryPulse=event(ordinary,state.player(),true);helper.effect(ordinaryPulse);
                require(!ordinaryPulse.isCancelled(),"Ordinary beacon is unaffected after recovery completes");
                report.set("checks.ordinary-beacon-unaffected-after-ready",true);
                return helper.persist(helper.plan(0,72,81,72).orElseThrow());
            }).thenCompose(plan->{
                var result=new CompletableFuture<Void>();main(()->{try{
                    require(helper.install(plan),"Beacon installs after durable record");
                    until(()->((org.bukkit.block.Beacon)world.getBlockAt(plan.x(),plan.y(),plan.z()).getState()).getTier()==1,240,"Vanilla tier-one activation").whenComplete((v,error)->{
                        if(error!=null){result.completeExceptionally(error);return;}
                        try{verify(plan,state,report);result.complete(null);}catch(Throwable failure){result.completeExceptionally(failure);}
                    });
                }catch(Throwable error){result.completeExceptionally(error);}});return result;
            });
        }catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            try{
                if(helper!=null)helper.close();io.shutdown();
                if(world!=null){require(world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"All beacon tickets released");require(plugin.getServer().unloadWorld(world,true),"Fixture unloads cleanly");}
                report.set("status",error==null?"passed":"failed");report.set("platform",plugin.getServer().getVersion());
                report.set("limits",List.of("Dedicated local world; real Beacon tier/effect configuration and event objects.","Player is an interface recorder passed to the actual production event handler; no real-client receipt or potion countdown is claimed.","No direct potion add/remove is allowed by the recorder; the five-second lifetime is verified in the native event payload."));
                var file=plugin.getDataFolder().toPath().resolve("buff/report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
                if(error!=null)throw new CompletionException(error);
                sender.sendMessage("BEACON BUFF SUCCESS speed-I=100ticks radius=24 alive-only=OK stronger-effects=preserved path="+file.toAbsolutePath());
            }catch(Throwable failure){sender.sendMessage("BEACON BUFF FAILED "+failure);plugin.getLogger().log(Level.SEVERE,"BEACON BUFF FAILED",failure);}
            finally{busy=false;}
        }));
    }
    private void verify(AirdropBeaconLedger.Plan plan,Viewer viewer,YamlConfiguration report){
        Block block=world.getBlockAt(plan.x(),plan.y(),plan.z());var beacon=(org.bukkit.block.Beacon)block.getState();
        require(beacon.getTier()==1&&beacon.getPrimaryEffect()!=null&&beacon.getPrimaryEffect().getType().equals(PotionEffectType.SPEED)
                &&beacon.getPrimaryEffect().getAmplifier()==0&&beacon.getSecondaryEffect()==null&&beacon.getEffectRange()==24,"Only native speed I is configured at range 24");
        Player player=viewer.player();viewer.world=world;viewer.location=new Location(world,plan.x()+.5,plan.y()+.5,plan.z()+.5);
        var accepted=event(block,player,true);helper.effect(accepted);speed(accepted);
        viewer.location.add(24,0,0);accepted=event(block,player,true);helper.effect(accepted);speed(accepted);
        viewer.location.add(.001,0,0);reject(block,player,true,"Outside radius");
        viewer.location=new Location(world,plan.x()+.5,plan.y()+.5+24.001,plan.z()+.5);reject(block,player,true,"Vertical distance is bounded too");
        viewer.location=new Location(world,plan.x()+20.5,plan.y()+.5,plan.z()+20.5);reject(block,player,true,"Cube corner outside true sphere");
        viewer.location=new Location(world,plan.x()+.5,plan.y()+.5,plan.z()+.5);
        reject(block,player,false,"Secondary effect never applies");
        viewer.participant=false;reject(block,player,true,"Nonparticipants and eliminated members are rejected");viewer.participant=true;
        viewer.mode=GameMode.SPECTATOR;reject(block,player,true,"Spectator game mode rejected");viewer.mode=GameMode.SURVIVAL;
        viewer.dead=true;reject(block,player,true,"Dead player rejected");viewer.dead=false;
        viewer.online=false;reject(block,player,true,"Offline player rejected");viewer.online=true;
        viewer.world=plugin.getServer().getWorlds().stream().filter(value->!value.getUID().equals(world.getUID())).findFirst().orElseThrow();reject(block,player,true,"Another world rejected");viewer.world=world;
        for(var existing:List.of(new PotionEffect(PotionEffectType.SPEED,1,1),new PotionEffect(PotionEffectType.SPEED,1200,0),new PotionEffect(PotionEffectType.SPEED,-1,0))){
            viewer.current=existing;reject(block,player,true,"Stronger/longer/infinite speed preserved");require(viewer.current==existing,"Original effect was not modified");
        }
        viewer.current=new PotionEffect(PotionEffectType.SPEED,20,0);accepted=event(block,player,true);helper.effect(accepted);speed(accepted);
        var secondary=event(block,player,false);secondary.setEffect(new PotionEffect(PotionEffectType.REGENERATION,200,0));helper.effect(secondary);require(secondary.isCancelled(),"An unrelated secondary type cannot leak");
        var original=viewer.current;helper.remove(plan.stage());require(block.getType()!=Material.BEACON&&helper.size()==0,"Cleanup removes native source");
        var inactive=event(block,player,true);new PaperAirdropBuff(p->true).apply(inactive,false);require(inactive.isCancelled()&&viewer.current==original,"Inactive filter stops refresh without deleting player effects");
        report.set("beacon.tier",1);report.set("beacon.effect","SPEED I");report.set("beacon.radius",24);report.set("beacon.duration-ticks",100);
        report.set("checks.active-alive-only",true);report.set("checks.no-secondary",true);report.set("checks.spherical-range-and-other-world",true);
        report.set("checks.stronger-longer-infinite-effects-preserved",true);report.set("checks.cleanup-stops-refresh-without-removing-effects",true);
    }
    private void reject(Block block,Player player,boolean primary,String message){var event=event(block,player,primary);helper.effect(event);require(event.isCancelled(),message);}
    private static BeaconEffectEvent event(Block block,Player player,boolean primary){return new BeaconEffectEvent(block,new PotionEffect(PotionEffectType.SPEED,220,0),player,primary);}
    private static void speed(BeaconEffectEvent event){require(!event.isCancelled()&&event.getEffect().getType().equals(PotionEffectType.SPEED)&&event.getEffect().getAmplifier()==0&&event.getEffect().getDuration()==100,"Accepted pulse is exactly five seconds of speed I");}
    private static final class Viewer {
        World world;Location location;boolean participant=true,online=true,dead;GameMode mode=GameMode.SURVIVAL;PotionEffect current;
        Player player(){return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()){
            case "getUniqueId"->UUID.fromString("11111111-1111-1111-1111-111111111118");case "getWorld"->world;case "getLocation"->location.clone();
            case "isOnline"->online;case "isDead"->dead;case "getGameMode"->mode;case "getPotionEffect"->current;
            case "removePotionEffect","addPotionEffect"->throw new AssertionError("No direct player effect mutation permitted");
            case "toString"->"BeaconBuffPlayerRecorder";default->throw new UnsupportedOperationException(method.getName());
        });}
    }
    private CompletableFuture<Void> until(BooleanSupplier condition,int limit,String description){
        var result=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();result.complete(null);}else if(++ticks>=limit)throw new IllegalStateException("Timed out: "+description);}catch(Throwable error){cancel();result.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return result;
    }
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Beacon buff assertion: "+message);}
}
