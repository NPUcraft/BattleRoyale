package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.flight.FlightRoute;
import com.npucraft.battleroyale.player.PlayerState;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Entire deployment remains STARTING. A real air-only block platform moves on a preloaded course.
 * No RUNNING checkpoint can contain temporary flight equipment or platform blocks.
 */
public final class PaperFlightDeployment implements Listener,AutoCloseable {
    public enum Phase { LOADING,BOARDING,FLYING,LANDING,COMPLETE }
    public record Progress(int completed,int total,Phase phase) {}
    private enum Presence { ONBOARD,DESCENDING,DONE }
    private static final long SECOND=1_000_000_000L;
    private final JavaPlugin plugin;
    private final GameSession session;
    private final World world;
    private final java.util.function.Function<UUID,Player> lookup;
    private final java.util.function.LongSupplier nanos;
    private final Map<UUID,Location> fallbacks=new LinkedHashMap<>();
    private final Map<UUID,Presence> presence=new LinkedHashMap<>();
    private final Map<UUID,Long> departed=new HashMap<>();
    private final Set<FlightRoute.Chunk> tickets=new LinkedHashSet<>();
    private final FlightRoute route;
    private final int height;
    private final List<FlightRoute.Cell> corridor;
    private final ArrayDeque<FlightRoute.Chunk> loading;
    private final CompletableFuture<Void> drained=new CompletableFuture<>();
    private PaperFlightEquipment equipment;
    private Platform platform;
    private CompletableFuture<Chunk> pending;
    private FlightRoute.Chunk pendingChunk;
    private BukkitTask task;
    private Runnable ready;
    private Consumer<Throwable> failed;
    private Phase phase=Phase.LOADING;
    private long started,phaseStarted;
    private int inspected,tick;
    private boolean startedOnce,stopped,closing,failureReported;
    public PaperFlightDeployment(JavaPlugin plugin,GameSession session,Map<UUID,Location> safeFallbacks,RandomGenerator random){
        this(plugin,session,safeFallbacks,random,id->plugin.getServer().getPlayer(id),System::nanoTime);
    }
    /** Injectable public-API player lookup and monotonic clock for isolated lifecycle verification. */
    public PaperFlightDeployment(JavaPlugin plugin,GameSession session,Map<UUID,Location> safeFallbacks,RandomGenerator random,java.util.function.Function<UUID,Player> lookup,java.util.function.LongSupplier nanos){
        this.plugin=Objects.requireNonNull(plugin);this.session=Objects.requireNonNull(session);
        this.lookup=Objects.requireNonNull(lookup);this.nanos=Objects.requireNonNull(nanos);
        world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()),"Flight world missing");
        if(!safeFallbacks.keySet().equals(session.players().keySet()))throw new IllegalArgumentException("Flight needs one safe fallback per frozen starter");
        safeFallbacks.forEach((id,at)->{if(at==null||!world.equals(at.getWorld()))throw new IllegalArgumentException("Flight fallback world mismatch");fallbacks.put(id,at.clone());});
        route=FlightRoute.random(session.initialZone().orElseThrow(),random);height=world.getMaxHeight()-16;
        if(height<world.getMinHeight()+32||fallbacks.values().stream().anyMatch(at->at.getY()+24>=height))
            throw new IllegalArgumentException("Insufficient safe airspace above the planned landing sites");
        corridor=route.corridor(height);
        var chunks=new LinkedHashSet<>(route.chunks());
        fallbacks.values().forEach(at->chunks.add(new FlightRoute.Chunk(at.getBlockX()>>4,at.getBlockZ()>>4)));
        if(chunks.size()>384)throw new IllegalArgumentException("Flight chunk budget exceeded");
        loading=new ArrayDeque<>(chunks);
    }
    public void start(Runnable ready,Consumer<Throwable> failed){
        requireMain();if(startedOnce||stopped)throw new IllegalStateException("Flight already started or stopped");
        startedOnce=true;this.ready=Objects.requireNonNull(ready);this.failed=Objects.requireNonNull(failed);started=nanos.getAsLong();phaseStarted=started;
        equipment=new PaperFlightEquipment(plugin,session.sessionId(),this::fail);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public Progress progress(){return new Progress((int)presence.values().stream().filter(value->value==Presence.DONE).count(),fallbacks.size(),phase);}
    public Location fallback(UUID player){var value=fallbacks.get(player);return value==null?null:value.clone();}
    public FlightRoute route(){return route;}
    public int platformBlocks(){return platform==null?0:platform.size();}
    public int ticketCount(){return tickets.size();}
    private void tick(){
        if(stopped)return;
        try{
            if(session.state()!=GameState.STARTING)throw new IllegalStateException("Flight outlived STARTING");
            long now=nanos.getAsLong();tick++;
            if(phase==Phase.LOADING){load(now);return;}
            updatePassengers(now);
            if(stopped)return;
            if(progress().completed()==fallbacks.size()){complete();return;}
            if(phase==Phase.BOARDING&&now-phaseStarted>=FlightRoute.BOARDING_SECONDS*SECOND){phase=Phase.FLYING;phaseStarted=now;}
            if(phase==Phase.FLYING&&tick%3==0){
                double fraction=(double)(now-phaseStarted)/(FlightRoute.FLIGHT_SECONDS*SECOND);
                move(route.center(fraction,height));
                if(fraction>=1){for(UUID id:fallbacks.keySet())if(presence.get(id)==Presence.ONBOARD)eject(id,now);platform.close();phase=Phase.LANDING;phaseStarted=now;}
            }
        }catch(Throwable error){fail(error);}
    }
    private void load(long now){
        if(now-started>FlightRoute.LOADING_SECONDS*SECOND)throw new IllegalStateException("Flight course loading exceeded 45 seconds");
        if(pending!=null){
            if(!pending.isDone())return;
            Chunk chunk=pending.join();pending=null;
            if(chunk==null)throw new IllegalStateException("Flight chunk load returned null");
            PaperChunkTickets.acquire(plugin,world,pendingChunk.x(),pendingChunk.z());tickets.add(pendingChunk);pendingChunk=null;
        }
        if(!loading.isEmpty()){
            pendingChunk=loading.removeFirst();pending=world.getChunkAtAsync(pendingChunk.x(),pendingChunk.z(),true);return;
        }
        for(int i=0;i<2048&&inspected<corridor.size();i++,inspected++){
            var at=corridor.get(inspected);
            if(!world.getBlockAt(at.x(),at.y(),at.z()).getType().isAir())throw new IllegalStateException("Flight corridor is obstructed; existing terrain was preserved");
        }
        if(inspected<corridor.size())return;
        platform=new Platform(world,route);platform.move(route.center(0,height));board(now);
    }
    private void board(long now){
        phase=Phase.BOARDING;phaseStarted=now;int index=0;
        for(UUID id:fallbacks.keySet()){
            var player=lookup.apply(id);
            if(presence.get(id)==Presence.DONE||player==null||!player.isOnline()||session.players().get(id).state()==PlayerState.DISCONNECTED){presence.put(id,Presence.DONE);continue;}
            if(player.isDead())throw new IllegalStateException("Cannot board a dead player");
            equipment.equip(player);
            // The central fuselage gives every configured starter a collision-bearing floor.
            int x=index%5-2,z=(index/5)%11-5;index++;
            var at=route.translate(route.center(0,height),x,1,z);
            Location target=new Location(world,at.x()+.5,at.y(),at.z()+.5,route.yaw(),10);
            if(!player.teleport(target))throw new IllegalStateException("Aircraft boarding teleport rejected");
            player.setVelocity(new Vector());player.setFallDistance(0);presence.put(id,Presence.ONBOARD);
            player.sendMessage(UiText.message(player,"已登机：走出机翼或跳离平台即可跳伞；临时鞘翅落地自动收回。","On board: walk off a wing or jump off to deploy. Your temporary elytra is removed on landing."));
        }
    }
    private void updatePassengers(long now){
        for(UUID id:fallbacks.keySet()){
            Presence state=presence.get(id);Player player=lookup.apply(id);
            if(state==Presence.DONE){
                if(player!=null&&player.isOnline()&&session.players().get(id).state()!=PlayerState.DISCONNECTED&&(player.isDead()||!player.getWorld().equals(world)))
                    throw new IllegalStateException("Landed participant left the prepared match world");
                continue;
            }
            if(player==null||!player.isOnline()){presence.put(id,Presence.DONE);continue;}
            if(player.isDead())throw new IllegalStateException("Participant died during flight");
            if(!player.getWorld().equals(world))throw new IllegalStateException("Flight participant left the match world");
            if(!equipment.equipped(player))throw new IllegalStateException("Temporary flight equipment was replaced");
            var at=player.getLocation();
            if(state==Presence.ONBOARD){
                boolean supported=platform.supports(at)&&at.getY()>=height+.65&&at.getY()<=height+2.05&&!player.isGliding();
                if(!supported){depart(id,player,now);state=Presence.DESCENDING;}
            }
            if(state==Presence.DESCENDING){
                if(player.isInLava()||at.getY()<world.getMinHeight()+4||session.initialZone().orElseThrow().distanceOutside(at.getX(),at.getZ())>256){
                    safeReturn(id,player);continue;
                }
                // Player.isOnGround is client-controlled and may remain false while gliding on a
                // fractional surface. Inspect actual collision components before the timeout fallback.
                if(at.getY()<height-2&&(PaperPlayerLanding.safeStanding(at)||player.isInWater())){
                    if(!session.initialZone().orElseThrow().contains(at.getX(),at.getZ()))safeReturn(id,player);else landed(id,player);
                    continue;
                }
                if(now-departed.getOrDefault(id,now)>FlightRoute.LANDING_SECONDS*SECOND){safeReturn(id,player);continue;}
                if(!player.isInWater()&&!player.isGliding())player.setGliding(true);
            }
        }
    }
    private void depart(UUID id,Player player,long now){
        presence.put(id,Presence.DESCENDING);departed.put(id,now);player.setFallDistance(0);
        if(!player.isOnGround())player.setGliding(true);
        player.sendMessage(UiText.message(player,"跳伞中：调整视角滑翔，落地后等待其他玩家。","Deploying: steer with your view; wait for the others after landing."));
    }
    private void eject(UUID id,long now){
        Player player=lookup.apply(id);if(player==null||!player.isOnline()){presence.put(id,Presence.DONE);return;}
        var center=route.center(1,height);var at=route.translate(center,FlightRoute.WING+3,-1,0);
        var location=new Location(world,at.x()+.5,at.y(),at.z()+.5,route.yaw(),15);
        if(!player.teleport(location))throw new IllegalStateException("End-of-route deployment teleport rejected");
        depart(id,player,now);player.setVelocity(new Vector(route.dx()*.65,-.15,route.dz()*.65));player.setGliding(true);
    }
    private void landed(UUID id,Player player){
        equipment.restore(player);presence.put(id,Presence.DONE);departed.remove(id);
        player.sendMessage(UiText.message(player,"已落地，原胸甲已恢复；等待所有玩家完成跳伞。","Landed. Your chest armor is restored; waiting for all players to deploy."));
    }
    private void safeReturn(UUID id,Player player){
        Location target=fallbacks.get(id);
        if(!PaperPlayerLanding.safeStanding(target))throw new IllegalStateException("Prepared flight fallback is no longer safe");
        if(!player.teleport(target))throw new IllegalStateException("Flight fallback teleport rejected");
        player.setVelocity(new Vector());landed(id,player);
    }
    private void move(FlightRoute.Cell next){
        var old=platform.center();if(next.equals(old))return;
        var passengers=new LinkedHashMap<Player,Location>();
        for(var entry:presence.entrySet())if(entry.getValue()==Presence.ONBOARD){
            Player player=lookup.apply(entry.getKey());if(player!=null&&player.isOnline()){
                Location target=player.getLocation().add(next.x()-old.x(),0,next.z()-old.z());passengers.put(player,target);
            }
        }
        platform.move(next);
        for(var passenger:passengers.entrySet()){
            if(!passenger.getKey().teleport(passenger.getValue()))throw new IllegalStateException("Moving platform teleport rejected");
            passenger.getKey().setFallDistance(0);passenger.getKey().setVelocity(new Vector());
        }
    }
    /** No completion callback here: the caller first captures and relocates the offline body. */
    public Location disconnect(Player player){
        requireMain();UUID id=player.getUniqueId();if(!fallbacks.containsKey(id))return null;
        if(equipment!=null)equipment.restore(player);
        presence.put(id,Presence.DONE);departed.remove(id);return fallback(id);
    }
    private void complete(){
        for(UUID id:fallbacks.keySet()){
            Player player=lookup.apply(id);
            if(player!=null&&player.isOnline()&&session.players().get(id).state()!=PlayerState.DISCONNECTED&&(player.isDead()||!player.getWorld().equals(world)))
                throw new IllegalStateException("Participant no longer ready to enter the match");
        }
        phase=Phase.COMPLETE;stop().whenComplete((unused,error)->onMain(()->{
            if(closing)return;
            try{if(error!=null)throw new CompletionException(error);ready.run();}
            catch(Throwable failure){if(!failureReported){failureReported=true;failed.accept(failure);}}
        }));
    }
    private void fail(Throwable error){
        if(failureReported||closing)return;failureReported=true;
        stop().whenComplete((unused,cleanup)->{if(cleanup!=null)error.addSuppressed(cleanup);if(!closing)onMain(()->failed.accept(error));});
    }
    /**
     * Idempotent: synchronously disarms every player mutation and restores equipment/platform.
     * The returned future only drains a pending chunk request; no later callback writes player state.
     */
    public CompletableFuture<Void> stop(){
        requireMain();if(stopped)return drained;stopped=true;
        if(task!=null)task.cancel();HandlerList.unregisterAll(this);
        Throwable error=null;
        try{if(equipment!=null)equipment.close();}catch(Throwable failure){error=failure;}
        try{if(platform!=null)platform.close();}catch(Throwable failure){if(error==null)error=failure;else error.addSuppressed(failure);}
        for(var chunk:List.copyOf(tickets))try{PaperChunkTickets.release(plugin,world,chunk.x(),chunk.z());}catch(Throwable failure){if(error==null)error=failure;else error.addSuppressed(failure);}
        tickets.clear();loading.clear();
        Throwable result=error;var request=pending;
        if(request==null||request.isDone()){if(result==null)drained.complete(null);else drained.completeExceptionally(result);}
        else request.whenComplete((chunk,loadFailure)->{if(result==null)drained.complete(null);else drained.completeExceptionally(result);});
        return drained;
    }
    @Override public void close(){
        requireMain();closing=true;stop();
        if(pending!=null)pending.cancel(false);
    }
    private void requireMain(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Flight deployment requires the server thread");}
    private void onMain(Runnable action){if(Bukkit.isPrimaryThread())action.run();else if(plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,action);}
    private boolean owned(org.bukkit.block.Block block){return platform!=null&&platform.owns(block);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void breakBlock(BlockBreakEvent event){if(owned(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void placeBlock(BlockPlaceEvent event){if(owned(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void flow(BlockFromToEvent event){if(owned(event.getToBlock())||owned(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void explode(EntityExplodeEvent event){event.blockList().removeIf(this::owned);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void explode(BlockExplodeEvent event){event.blockList().removeIf(this::owned);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void extend(BlockPistonExtendEvent event){if(event.getBlocks().stream().anyMatch(block->owned(block)||owned(block.getRelative(event.getDirection()))))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void retract(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(block->owned(block)||owned(block.getRelative(event.getDirection()))))event.setCancelled(true);}

    /** Public for native isolated probes. Changes only air; restores only its exact remaining blocks. */
    public static final class Platform implements AutoCloseable {
        private record Placed(BlockData original,BlockData installed) {}
        private final World world;private final FlightRoute route;
        private final Map<FlightRoute.Cell,Placed> blocks=new LinkedHashMap<>();
        private FlightRoute.Cell center;
        public Platform(World world,FlightRoute route){this.world=Objects.requireNonNull(world);this.route=Objects.requireNonNull(route);}
        public FlightRoute.Cell center(){return center;}
        public int size(){return blocks.size();}
        public boolean owns(org.bukkit.block.Block block){return world.equals(block.getWorld())&&blocks.containsKey(new FlightRoute.Cell(block.getX(),block.getY(),block.getZ()));}
        public boolean supports(Location at){
            if(center==null||!world.equals(at.getWorld()))return false;
            return blocks.containsKey(new FlightRoute.Cell(at.getBlockX(),center.y(),at.getBlockZ()));
        }
        private Map<FlightRoute.Cell,Material> shape(FlightRoute.Cell target){
            var next=new LinkedHashMap<FlightRoute.Cell,Material>();
            for(int z=-8;z<=8;z++)for(int x=-2;x<=2;x++)next.put(route.translate(target,x,0,z),x==0?Material.LIGHT_BLUE_CONCRETE:Material.WHITE_CONCRETE);
            for(int z=-2;z<=2;z++)for(int x=-8;x<=8;x++)next.put(route.translate(target,x,0,z),Math.abs(x)>=7?Material.LIGHT_BLUE_CONCRETE:Material.WHITE_CONCRETE);
            for(int z=6;z<=8;z++)for(int x=-5;x<=5;x++)next.put(route.translate(target,x,0,z),Material.WHITE_CONCRETE);
            for(int y=1;y<=2;y++)next.put(route.translate(target,0,y,8),Material.LIGHT_BLUE_CONCRETE);
            for(int x=-1;x<=1;x++)next.put(route.translate(target,x,1,-8),Material.CYAN_STAINED_GLASS);
            return next;
        }
        public void move(FlightRoute.Cell target){
            var next=shape(target);
            for(var entry:next.entrySet()){
                var at=entry.getKey();if(at.y()<world.getMinHeight()||at.y()>=world.getMaxHeight())throw new IllegalStateException("Plane outside world height");
                var block=world.getBlockAt(at.x(),at.y(),at.z());var old=blocks.get(at);
                if(old==null&&!block.getType().isAir())throw new IllegalStateException("Plane would overwrite terrain");
                if(old!=null&&!block.getBlockData().equals(old.installed()))throw new IllegalStateException("Plane block was modified externally");
            }
            for(var entry:next.entrySet()){
                var at=entry.getKey();var block=world.getBlockAt(at.x(),at.y(),at.z());var old=blocks.get(at);
                BlockData installed=entry.getValue().createBlockData();
                blocks.put(at,new Placed(old==null?block.getBlockData().clone():old.original(),installed));
                if(!block.getBlockData().equals(installed))block.setBlockData(installed,false);
            }
            for(var at:List.copyOf(blocks.keySet()))if(!next.containsKey(at))restore(at);
            center=target;
        }
        private void restore(FlightRoute.Cell at){
            var value=blocks.remove(at);var block=world.getBlockAt(at.x(),at.y(),at.z());
            if(block.getBlockData().equals(value.installed()))block.setBlockData(value.original(),false);
        }
        @Override public void close(){
            Throwable first=null;
            for(var at:List.copyOf(blocks.keySet()))try{restore(at);}catch(Throwable error){if(first==null)first=error;else first.addSuppressed(error);}
            center=null;if(first!=null)throw new IllegalStateException("Could not restore aircraft blocks",first);
        }
    }
}