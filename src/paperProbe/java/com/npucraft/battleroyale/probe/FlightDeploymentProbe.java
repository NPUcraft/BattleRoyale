package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.flight.FlightRoute;
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
import java.util.function.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.java.JavaPlugin;

/** Native blocks/items and recorded Player API transitions; no simulated recorder is a native client. */
public final class FlightDeploymentProbe {
    private final JavaPlugin plugin;
    private final YamlConfiguration report=new YamlConfiguration();
    private final Map<UUID,Viewer> viewers=new LinkedHashMap<>();
    private World world;private PaperFlightDeployment flight;private long now;private int ready,failures;private Throwable flightFailure;private boolean busy,throwReady;
    public FlightDeploymentProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.flight"),"Explicit isolated flight flag");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No native players online");require(!busy,"Probe already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26flight all");
        busy=true;CompletableFuture<Void> work;
        try{setup();platform();equipment();work=success().thenCompose(unused->cancel()).thenCompose(unused->disconnect()).thenCompose(unused->rejectedTeleport()).thenCompose(unused->loadingCancellation()).thenCompose(unused->readyFailure());}
        catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            try{
                if(flight!=null)flight.close();
                require(world==null||tickets()==0,"Flight released every plugin chunk ticket");
                if(world!=null)require(plugin.getServer().unloadWorld(world,true),"Flight fixture unloads normally");
                report.set("status",error==null?"passed":"failed");if(error!=null)report.set("failure",error.toString());
                report.set("limits",List.of("Dedicated generated local world; no production map or native player is used.","Platform collision blocks, world mutation, native item serialization and event objects use actual Paper APIs.","Player movement, glide state and clock are interface recorders; this is not a client physics, passenger packet or multiplayer match validation."));
                var file=plugin.getDataFolder().toPath().resolve("flight/report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
                if(error!=null)throw new CompletionException(error);
                sender.sendMessage("FLIGHT DEPLOYMENT SUCCESS platform=real-blocks equipment=restored lifecycle=bounded cleanup=OK path="+file.toAbsolutePath());
            }catch(Throwable failure){sender.sendMessage("FLIGHT DEPLOYMENT FAILED "+failure);plugin.getLogger().log(Level.SEVERE,"Flight probe failed",failure);}
            finally{busy=false;}
        }));
    }
    private void setup(){
        var key=new NamespacedKey("battleroyale_probe","flight_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
        for(int x=-4;x<=8;x++)for(int z=-4;z<=4;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        report.set("platform",plugin.getServer().getVersion());report.set("world",world.getName());
    }
    private void platform(){
        var route=new FlightRoute(0,0,32,0);var plane=new PaperFlightDeployment.Platform(world,route);
        var first=route.center(0,220);plane.move(first);
        require(plane.size()>160,"Aircraft has substantial real geometry");require(world.getBlockAt(0,220,0).getType().isSolid(),"Fuselage is a collision-bearing block");
        require(plane.supports(new Location(world,.5,221,.5)),"Feet rest on actual platform");
        var changed=world.getBlockAt(0,220,0);changed.setType(Material.DIAMOND_BLOCK,false);
        boolean rejected=false;try{plane.move(route.center(.1,220));}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"External modification aborts platform move");plane.close();require(changed.getType()==Material.DIAMOND_BLOCK,"Cleanup preserves externally replaced block");changed.setType(Material.AIR,false);
        plane=new PaperFlightDeployment.Platform(world,route);plane.move(first);
        world.getBlockAt(32,220,0).setType(Material.OBSIDIAN,false);rejected=false;
        try{plane.move(route.center(1,220));}catch(IllegalStateException expected){rejected=true;}
        require(rejected&&world.getBlockAt(32,220,0).getType()==Material.OBSIDIAN,"Destination obstruction is never overwritten");
        world.getBlockAt(32,220,0).setType(Material.AIR,false);plane.move(route.center(1,220));
        require(world.getBlockAt(0,220,0).getType().isAir()&&world.getBlockAt(32,220,0).getType().isSolid(),"Old floor is restored and new floor moves to endpoint");
        plane.close();plane.close();for(var at:route.corridor(220))require(world.getBlockAt(at.x(),at.y(),at.z()).getType().isAir(),"Exact air restoration after movement");
        report.set("native-platform","Collision floor, wings/tail/cockpit, moved blocks, destination obstruction, external ownership and idempotent cleanup");
    }
    private void equipment(){
        var viewer=new Viewer(0);UUID session=UUID.randomUUID();var errors=new ArrayList<Throwable>();
        var equipment=new PaperFlightEquipment(plugin,session,errors::add);equipment.equip(viewer.player);ItemStack elytra=viewer.items[38].clone();
        require(equipment.equipped(viewer.player),"Temporary native elytra is bound to player/session");
        var item=world.dropItem(new Location(world,2.5,81,2.5),new ItemStack(Material.STONE));item.setItemStack(elytra.clone());
        var drop=new PlayerDropItemEvent(viewer.player,item);equipment.drop(drop);require(drop.isCancelled(),"Drop blocked");
        var swap=new PlayerSwapHandItemsEvent(viewer.player,elytra,new ItemStack(Material.STONE));equipment.swap(swap);require(swap.isCancelled(),"Hand swap blocked");
        var damage=new PlayerItemDamageEvent(viewer.player,elytra,3);equipment.damage(damage);require(damage.isCancelled(),"Temporary elytra cannot lose durability");
        var pickup=new EntityPickupItemEvent(viewer.player,item,0);equipment.pickup(pickup);require(pickup.isCancelled(),"Entity pickup blocked");
        Inventory source=Bukkit.createInventory(null,9),target=Bukkit.createInventory(null,9);source.setItem(0,elytra.clone());
        var move=new InventoryMoveItemEvent(source,elytra,target,true);equipment.move(move);require(move.isCancelled(),"Automated inventory transfer blocked");
        viewer.items[0]=elytra.clone();viewer.cursor=elytra.clone();equipment.restore(viewer.player);
        require(viewer.items[0]==null&&viewer.cursor==null,"Duplicate temporary items removed");
        require(Arrays.equals(viewer.original,viewer.items[38].serializeAsBytes()),"Original native chest metadata restored exactly");
        int writes=viewer.writes;equipment.restore(viewer.player);equipment.close();require(viewer.writes==writes,"Restoration is exactly once");
        require(errors.isEmpty(),"No equipment guard failures");item.remove();
        report.set("native-equipment","Original item bytes and metadata; bound elytra; drop/swap/damage/pickup/hopper guards; duplicate removal; exactly-once restore");
    }
    private void begin(int count,boolean reject){
        viewers.clear();for(int i=0;i<count;i++){var viewer=new Viewer(i);viewer.rejectTeleport=reject;viewers.put(viewer.id,viewer);}
        var room=new RoomDefinition("flight","Flight fixture",1,Math.max(2,count),1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(1));
        var map=new MapTemplate("fixture","Flight fixture",world.getWorldPath(),new PlayableArea(-1000,1000,-1000,1000));
        var session=GameSession.waiting(UUID.randomUUID(),room,Instant.now());viewers.keySet().forEach(session::join);session.prepare(map,new Random(1));session.initialZone(new Zone(0,0,500));
        session.starting(new GameWorld(session.sessionId(),room.id(),world.getName(),world.getWorldPath(),map));
        var fallbacks=new LinkedHashMap<UUID,Location>();viewers.values().forEach(viewer->fallbacks.put(viewer.id,viewer.location.clone()));
        now=0;ready=0;failures=0;throwReady=false;flightFailure=null;
        flight=new PaperFlightDeployment(plugin,session,fallbacks,new Random(12),id->{var viewer=viewers.get(id);return viewer==null||!viewer.online?null:viewer.player;},()->now);
        flight.start(()->{ready++;if(throwReady)throw new IllegalStateException("Deliberate ready callback failure");},error->{failures++;flightFailure=error;});
    }
    private CompletableFuture<Void> boarding(){return await(()->flight.progress().phase()==PaperFlightDeployment.Phase.BOARDING||flightFailure!=null,1400).thenRun(()->require(flightFailure==null,"Boarding succeeds: "+flightFailure));}
    private CompletableFuture<Void> success(){
        begin(2,false);var all=List.copyOf(viewers.values());var first=all.get(0);var last=all.get(1);
        return boarding().thenCompose(unused->{
            require(flight.platformBlocks()>160&&tickets()>0,"Platform and route tickets present before flight");
            require(all.stream().allMatch(viewer->viewer.items[38].getType()==Material.ELYTRA&&viewer.onGround()),"Every recorded player boards a solid native floor");
            var before=first.location.clone();now=6_000_000_000L;
            return await(()->flight.progress().phase()==PaperFlightDeployment.Phase.FLYING,30).thenCompose(value->{now=28_000_000_000L;return delay(5);})
                    .thenRun(()->require(first.location.distanceSquared(before)>100&&first.onGround(),"Moving platform carries standing players"));
        }).thenCompose(unused->{
            var route=flight.route();first.location.add(route.dz()*20,-4,-route.dx()*20);
            return await(()->first.gliding,30);
        }).thenCompose(unused->{first.location=flight.fallback(first.id);return await(()->flight.progress().completed()==1,30);})
          .thenCompose(unused->{
              require(Arrays.equals(first.original,first.items[38].serializeAsBytes())&&ready==0,"Landing immediately restores armor without starting before others");
              now=60_000_000_000L;return await(()->flight.progress().phase()==PaperFlightDeployment.Phase.LANDING,30);
          }).thenCompose(unused->{
              require(last.gliding&&last.items[38].getType()==Material.ELYTRA,"Route end ejects remaining passenger");now=151_000_000_000L;
              return await(()->ready==1||flightFailure!=null,30);
          }).thenRun(()->{
              require(flightFailure==null&&ready==1,"All landed or bounded fallback triggers ready once");
              require(last.location.equals(flight.fallback(last.id))&&Arrays.equals(last.original,last.items[38].serializeAsBytes()),"90-second fallback restores native original equipment at safe ground");
              require(flight.platformBlocks()==0&&tickets()==0&&flight.stop()==flight.stop(),"Success cleans platform/tickets and stop is idempotent");
              report.set("recorded-lifecycle","Actual platform movement with Player API recorders; walk-off gliding; immediate landing restore; end-route ejection; 90s fallback; one ready callback");
          });
    }
    private CompletableFuture<Void> cancel(){
        begin(1,false);var viewer=viewers.values().iterator().next();
        return boarding().thenCompose(unused->{
            CompletableFuture<Void> stopped=flight.stop();require(Arrays.equals(viewer.original,viewer.items[38].serializeAsBytes()),"Stop restores armor synchronously");
            int writes=viewer.writes;now=300_000_000_000L;
            return stopped.thenCompose(value->delay(5)).thenRun(()->{
                require(viewer.writes==writes&&ready==0&&tickets()==0,"No delayed inventory mutation or ready callback after cancellation");
                report.set("cancel","Synchronous armor restoration; no delayed player mutations or leaked tickets");
            });
        });
    }
    private CompletableFuture<Void> disconnect(){
        begin(1,false);var viewer=viewers.values().iterator().next();
        return boarding().thenCompose(unused->{
            Location fallback=flight.disconnect(viewer.player);require(ready==0,"Disconnect does not reenter ready before offline capture");
            require(fallback.equals(flight.fallback(viewer.id))&&Arrays.equals(viewer.original,viewer.items[38].serializeAsBytes()),"Disconnect returns ground location after armor restore");
            viewer.online=false;return await(()->ready==1,30);
        }).thenRun(()->{require(tickets()==0,"Disconnected completion cleans all tickets");report.set("disconnect","Armor restored before return; ground fallback; ready deferred to next tick");});
    }
    private CompletableFuture<Void> rejectedTeleport(){
        begin(1,true);var viewer=viewers.values().iterator().next();
        return await(()->flightFailure!=null,1400).thenRun(()->{
            require(ready==0&&flight.platformBlocks()==0&&tickets()==0,"Rejected teleport cleans all resources without ready");
            require(Arrays.equals(viewer.original,viewer.items[38].serializeAsBytes()),"Rejected boarding restores original armor");
            report.set("rejected-teleport","False native teleport return aborts and restores original equipment/platform/tickets");
        });
    }
    private CompletableFuture<Void> readyFailure(){
        begin(1,false);throwReady=true;var viewer=viewers.values().iterator().next();
        return boarding().thenCompose(unused->{
            flight.disconnect(viewer.player);viewer.online=false;
            return await(()->flightFailure!=null,30);
        }).thenCompose(unused->delay(5)).thenRun(()->{
            require(ready==1&&failures==1,"Throwing ready callback reports failure exactly once");
            require(flightFailure.getMessage().contains("Deliberate ready callback failure"),"Original ready callback failure reaches failure handler");
            require(flight.platformBlocks()==0&&tickets()==0&&flight.stop().isDone(),"Ready callback failure leaves no platform or chunk tickets");
            require(Arrays.equals(viewer.original,viewer.items[38].serializeAsBytes()),"Equipment is restored before exceptional ready callback");
            report.set("ready-callback-failure","Deliberate ready exception reaches failed once; original armor restored; no platform or tickets remain");
        });
    }
    private CompletableFuture<Void> loadingCancellation(){
        begin(1,false);var viewer=viewers.values().iterator().next();
        return delay(1).thenCompose(unused->{
            int writes=viewer.writes;var stopped=flight.stop();
            return stopped.thenCompose(value->delay(5)).thenRun(()->{
                require(viewer.writes==writes&&ready==0&&tickets()==0,"Cancellation drains pending chunk work with no subsequent player mutation");
                report.set("loading-cancel","Pending chunk completion drained; no callback or equipment mutation after stop");
            });
        });
    }
    private int tickets(){return world.getPluginChunkTickets().entrySet().stream().filter(entry->entry.getKey().equals(plugin)).mapToInt(entry->entry.getValue().size()).sum();}
    private CompletableFuture<Void> await(BooleanSupplier condition,int limit){
        var future=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();future.complete(null);}else if(++ticks>=limit){cancel();future.completeExceptionally(new IllegalStateException("Flight probe timed out; phase="+flight.progress()+" error="+flightFailure));}}catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return future;
    }
    private CompletableFuture<Void> delay(int ticks){var future=new CompletableFuture<Void>();plugin.getServer().getScheduler().runTaskLater(plugin,()->future.complete(null),ticks);return future;}
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Flight assertion: "+message);}
    private final class Viewer {
        final UUID id=UUID.randomUUID();final ItemStack[] items=new ItemStack[41];final byte[] original;final PlayerInventory inventory;final Player player;
        Location location;ItemStack cursor;boolean online=true,gliding,rejectTeleport;int writes;
        Viewer(int index){
            location=new Location(world,index*3+.5,81,.5);
            ItemStack chest=new ItemStack(Material.DIAMOND_CHESTPLATE);var meta=chest.getItemMeta();meta.displayName(Component.text("Original <literal> armor "+index));meta.setUnbreakable(true);chest.setItemMeta(meta);items[38]=chest;original=chest.serializeAsBytes();
            inventory=(PlayerInventory)Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),new Class<?>[]{PlayerInventory.class},(self,method,args)->switch(method.getName()){
                case "getSize"->items.length;case "getItem"->items[(Integer)args[0]];case "getChestplate"->items[38];
                case "setItem"->{items[(Integer)args[0]]=(ItemStack)args[1];writes++;yield null;}case "setChestplate"->{items[38]=(ItemStack)args[0];writes++;yield null;}
                case "getContents"->items.clone();case "toString"->"FlightInventoryRecorder";
                default->throw new UnsupportedOperationException("Unexpected inventory API "+method);
            });
            player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->switch(method.getName()){
                case "getUniqueId"->id;case "getName"->"FlightRecorder";case "getServer"->plugin.getServer();case "locale"->Locale.ENGLISH;
                case "isOnline"->online;case "isDead"->false;case "getWorld"->world;case "getLocation"->location.clone();
                case "getInventory"->inventory;case "getItemOnCursor"->cursor;case "setItemOnCursor"->{cursor=(ItemStack)args[0];writes++;yield null;}
                case "teleport"->{if(rejectTeleport)yield false;location=((Location)args[0]).clone();yield true;}
                case "isOnGround"->onGround();case "isInWater","isInLava"->false;case "isGliding"->gliding;
                case "setGliding"->{gliding=(boolean)args[0];yield null;}case "setVelocity","setFallDistance","sendMessage"->null;
                case "toString"->"FlightPlayerInterfaceRecorder";case "hashCode"->id.hashCode();case "equals"->self==args[0];
                default->throw new UnsupportedOperationException("Unexpected player API "+method);
            });
        }
        boolean onGround(){return Math.abs(location.getY()-Math.rint(location.getY()))<.01&&world.getBlockAt(location.getBlockX(),location.getBlockY()-1,location.getBlockZ()).getType().isSolid();}
    }
}