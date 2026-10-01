package com.npucraft.battleroyale.paper;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import com.npucraft.battleroyale.loot.AirdropBeaconLedger;
import com.npucraft.battleroyale.loot.AirdropBeaconLedger.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import com.npucraft.battleroyale.loot.BeaconBuffPolicy;
import java.util.function.Predicate;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** Real vanilla beacon beams, scoped protection and compare-and-restore cleanup. Bukkit calls are owner-thread only. */
public final class PaperAirdropBeacons implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final World world;private final UUID session;private final Executor io;
    private final CompletableFuture<List<Plan>> recovery;
    private final Map<Integer,Plan> active=new LinkedHashMap<>();
    private final Map<Position,Plan> blocks=new HashMap<>();
    private final PaperAirdropBuff buff;
    private boolean ready,closed;
    private record Position(int x,int y,int z){}
    public PaperAirdropBeacons(JavaPlugin plugin,World world,UUID session,Executor io){this(plugin,world,session,io,player->false);}
    public PaperAirdropBeacons(JavaPlugin plugin,World world,UUID session,Executor io,Predicate<Player> eligible){
        this.plugin=plugin;this.world=world;this.session=session;this.io=io;buff=new PaperAirdropBuff(eligible);
        var path=world.getWorldPath();var worldId=world.getUID();
        recovery=CompletableFuture.supplyAsync(()->{try{return AirdropBeaconLedger.load(path,session,worldId);}catch(java.io.IOException error){throw new CompletionException(error);}},io);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    /** Recovery never creates a replacement beacon or repeats an airdrop. */
    public boolean recover(){
        if(closed)return false;if(ready)return true;if(!recovery.isDone())return false;
        for(var plan:recovery.join())restore(plan);
        ready=true;return true;
    }
    private NamespacedKey ownerKey(Plan plan){return new NamespacedKey(plugin,"airdrop_beacon_"+plan.stage());}
    private boolean owns(Chunk chunk,Plan plan){return plan.identity().equals(chunk.getPersistentDataContainer().get(ownerKey(plan),PersistentDataType.STRING));}
    /** Candidate stays in the already prepared crate chunk; no adjacent chunk is generated. */
    public Optional<Plan> plan(int stage,int crateX,int crateY,int crateZ){
        if(!ready||closed)return Optional.empty();
        for(int[] offset:new int[][]{{2,0},{-2,0},{0,2},{0,-2}}){
            int bx=crateX+offset[0],bz=crateZ+offset[1];
            if(((bx-1)>>4)!=(crateX>>4)||((bx+1)>>4)!=(crateX>>4)||((bz-1)>>4)!=(crateZ>>4)||((bz+1)>>4)!=(crateZ>>4))continue;
            if(crateY-1<world.getMinHeight()||crateY+1>=world.getMaxHeight())continue;
            if(world.getHighestBlockYAt(bx,bz,HeightMap.WORLD_SURFACE)>=crateY)continue;
            if(!clearEntities(bx,crateY,bz))continue;
            var cells=new ArrayList<Cell>();boolean safe=true;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                Block block=world.getBlockAt(bx+dx,crateY-1,bz+dz);
                if(!replaceable(block)||protectedBlock(block)){safe=false;break;}
                cells.add(new Cell(block.getX(),block.getY(),block.getZ(),block.getBlockData().getAsString(),"minecraft:iron_block"));
            }
            if(!safe)continue;
            for(int dy=0;dy<=1;dy++){
                Block block=world.getBlockAt(bx,crateY+dy,bz);
                if(!block.getType().isAir()||protectedBlock(block)){safe=false;break;}
                cells.add(new Cell(bx,crateY+dy,bz,block.getBlockData().getAsString(),dy==0?"minecraft:beacon":"minecraft:yellow_stained_glass"));
            }
            if(safe)return Optional.of(new Plan(session,world.getUID(),stage,bx,crateY,bz,cells));
        }
        return Optional.empty();
    }
    private boolean clearEntities(int x,int y,int z){return world.getNearbyEntities(new BoundingBox(x-1,y-1,z-1,x+2,y+2,z+2)).stream().noneMatch(entity->entity instanceof LivingEntity);}
    private boolean replaceable(Block block){
        Material type=block.getType();return !com.npucraft.battleroyale.loot.ValuableBlockPolicy.replaces(type.name())&&!(block.getState() instanceof TileState)&&!PaperSpawnTerrain.hazard(type)
                &&type!=Material.BEDROCK&&type!=Material.BARRIER&&(type.isAir()||type.isOccluding());
    }
    public CompletableFuture<Plan> persist(Plan plan){
        if(closed)throw new IllegalStateException("Beacon owner closed");var path=world.getWorldPath();
        return CompletableFuture.supplyAsync(()->{try{AirdropBeaconLedger.save(path,plan);return plan;}catch(java.io.IOException error){throw new CompletionException(error);}},io);
    }
    /** Called only after persist succeeds; any change during the write rejects this unused location. */
    public boolean install(Plan plan){
        if(closed||!ready||active.containsKey(plan.stage())||!session.equals(plan.session())||!world.getUID().equals(plan.world()))return false;
        if(world.getHighestBlockYAt(plan.x(),plan.z(),HeightMap.WORLD_SURFACE)>=plan.y()||!clearEntities(plan.x(),plan.y(),plan.z()))return false;
        for(var cell:plan.cells())if(!cell.original().equals(world.getBlockAt(cell.x(),cell.y(),cell.z()).getBlockData().getAsString())||protectedBlock(world.getBlockAt(cell.x(),cell.y(),cell.z())))return false;
        var chunks=chunks(plan);for(var chunk:chunks){PaperChunkTickets.acquire(plugin,world,chunk.getX(),chunk.getZ());chunk.getPersistentDataContainer().set(ownerKey(plan),PersistentDataType.STRING,plan.identity());}
        active.put(plan.stage(),plan);for(var cell:plan.cells())blocks.put(new Position(cell.x(),cell.y(),cell.z()),plan);
        try{
            for(var cell:plan.cells()){var block=world.getBlockAt(cell.x(),cell.y(),cell.z());WorldSanitizer.preserveGenerated(plugin,block);block.setBlockData(Bukkit.createBlockData(cell.placed()),false);}
            var beacon=(org.bukkit.block.Beacon)world.getBlockAt(plan.x(),plan.y(),plan.z()).getState();
            beacon.setPrimaryEffect(PotionEffectType.SPEED);beacon.setSecondaryEffect(null);beacon.setEffectRange(BeaconBuffPolicy.RADIUS);beacon.update(true,false);
            return true;
        }catch(RuntimeException error){remove(plan.stage());throw error;}
    }
    private Set<Chunk> chunks(Plan plan){var chunks=new LinkedHashSet<Chunk>();for(var cell:plan.cells())chunks.add(world.getChunkAt(cell.x()>>4,cell.z()>>4));return chunks;}
    private void restore(Plan plan){
        var chunks=chunks(plan);
        for(var cell:plan.cells()){
            var block=world.getBlockAt(cell.x(),cell.y(),cell.z());
            if(cell.restores(block.getBlockData().getAsString(),owns(block.getChunk(),plan)))block.setBlockData(Bukkit.createBlockData(cell.original()),false);
        }
        // Marker and blocks are saved together in the same chunk. Keep the immutable intention on disk:
        // a crash before chunk save retries; later player changes without this marker are never restored.
        for(var chunk:chunks)if(owns(chunk,plan))chunk.getPersistentDataContainer().remove(ownerKey(plan));
    }
    public void remove(int stage){
        var plan=active.get(stage);if(plan==null)return;
        restore(plan);active.remove(stage);for(var cell:plan.cells())blocks.remove(new Position(cell.x(),cell.y(),cell.z()));
        for(var chunk:chunks(plan))PaperChunkTickets.release(plugin,world,chunk.getX(),chunk.getZ());
    }
    public boolean ready(){return ready&&!closed;}
    public int size(){return active.size();}
    public Optional<Plan> active(int stage){return Optional.ofNullable(active.get(stage));}
    public boolean protectedBlock(Block block){return block.getWorld().getUID().equals(world.getUID())&&blocks.containsKey(new Position(block.getX(),block.getY(),block.getZ()));}
    private boolean beamColumn(Block block){return block.getWorld().getUID().equals(world.getUID())&&active.values().stream().anyMatch(plan->block.getX()==plan.x()&&block.getZ()==plan.z()&&block.getY()>=plan.y());}
    private boolean reserved(Block block){return protectedBlock(block)||beamColumn(block);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void breakBlock(BlockBreakEvent event){if(protectedBlock(event.getBlock())){event.setDropItems(false);event.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void damage(BlockDamageEvent event){if(protectedBlock(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void place(BlockPlaceEvent event){if(reserved(event.getBlockPlaced()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void multiPlace(BlockMultiPlaceEvent event){if(event.getReplacedBlockStates().stream().anyMatch(state->reserved(state.getBlock())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void interact(PlayerInteractEvent event){if(event.getClickedBlock()!=null&&protectedBlock(event.getClickedBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void bucketEmpty(PlayerBucketEmptyEvent event){
        // Paper supplies the actual changed block separately from the clicked block/face.
        // Old synthetic event constructors may omit it; do not guess and block unrelated terrain.
        if(event.getBlock()!=null&&reserved(event.getBlock()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void effect(BeaconEffectEvent event){
        // Restored native beacons can pulse before their durable ownership records have been loaded.
        if(!closed&&!ready&&event.getBlock().getWorld().getUID().equals(world.getUID())){event.setCancelled(true);return;}
        if(!protectedBlock(event.getBlock()))return;
        var plan=blocks.get(new Position(event.getBlock().getX(),event.getBlock().getY(),event.getBlock().getZ()));
        boolean valid=!closed&&ready&&owns(event.getBlock().getChunk(),plan)&&event.getBlock().getState() instanceof org.bukkit.block.Beacon beacon&&beacon.getTier()>0;
        buff.apply(event,valid);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void explode(EntityExplodeEvent event){event.blockList().removeIf(this::protectedBlock);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void explode(BlockExplodeEvent event){event.blockList().removeIf(this::protectedBlock);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void entityChange(EntityChangeBlockEvent event){if(reserved(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void extend(BlockPistonExtendEvent event){if(reserved(event.getBlock().getRelative(event.getDirection()))||event.getBlocks().stream().anyMatch(block->reserved(block)||reserved(block.getRelative(event.getDirection()))))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void retract(BlockPistonRetractEvent event){if(reserved(event.getBlock().getRelative(event.getDirection()))||event.getBlocks().stream().anyMatch(block->reserved(block)||reserved(block.getRelative(event.getDirection()))||reserved(block.getRelative(event.getDirection().getOppositeFace()))))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void flow(BlockFromToEvent event){if(reserved(event.getToBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void form(BlockFormEvent event){if(reserved(event.getBlock()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void grow(StructureGrowEvent event){event.getBlocks().removeIf(state->reserved(state.getBlock()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void fertilize(BlockFertilizeEvent event){event.getBlocks().removeIf(state->reserved(state.getBlock()));}
    /** No asynchronous Bukkit callback can create blocks after this boundary. */
    public CompletableFuture<Void> stop(){
        closed=true;for(int stage:List.copyOf(active.keySet()))remove(stage);HandlerList.unregisterAll(this);
        return recovery.handle((value,error)->null);
    }
    @Override public void close(){stop();}
}
