package com.lastsector.paper;

import com.lastsector.config.MatchContent;
import com.lastsector.loot.*;
import com.lastsector.service.GameScheduler;
import com.lastsector.session.GameSession;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.*;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.random.RandomGenerator;

/** One candidate per tick, async requested chunks, one generation per immutable InitialZone. */
public final class PaperLootRuntime {
    public enum State { NOT_STARTED, GENERATING, COMPLETE, FAILED }
    private final JavaPlugin plugin;
    private final GameSession session;
    private final UUID worldId;
    private final WorldSanitizer sanitizer;
    private final MatchContent content;
    private final NativeLootItems items;
    private final RandomGenerator random;
    private final NamespacedKey marker;
    private final GameScheduler scheduler;
    private final Queue<ContainerLootPoint> points=new ArrayDeque<>();
    private final Queue<GroundRequest> ground=new ArrayDeque<>();
    private final Set<Long> tickets=new HashSet<>();
    private final Set<String> filled=new HashSet<>();
    private final CompletableFuture<Void> result=new CompletableFuture<>();
    private CompletableFuture<?> pending;
    private List<CompletableFuture<Chunk>> requested=List.of();
    private Runnable inspect;
    private GameScheduler.Task task;
    private State state=State.NOT_STARTED;
    public State state(){return state;}
    private boolean cancelled;
    private int activePoints,skippedPoints,activeAreas,skippedAreas,groundItems,missedSpawns;
    public PaperLootRuntime(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,MatchContent content,NativeLootItems items,
            RandomGenerator random,NamespacedKey marker,GameScheduler scheduler) {
        this.plugin=plugin; this.session=session; this.sanitizer=sanitizer; this.content=content; this.items=items; this.random=random; this.marker=marker; this.scheduler=scheduler;
        worldId=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName())).getUID();
    }
    private World world() { return Objects.requireNonNull(plugin.getServer().getWorld(worldId),"Match world unloaded"); }
    public CompletableFuture<Void> generate() {
        if(state!=State.NOT_STARTED) return result;
        state=State.GENERATING;
        var metadata=content.maps().get(session.selectedMap().orElseThrow().id()); var initial=session.initialZone().orElseThrow();
        for(var point:metadata.containers()) {
            if(initial.contains(point.x(),point.z())) { points.add(point); activePoints++; } else skippedPoints++;
        }
        for(var area:metadata.areas()) {
            var bounds=area.intersection(initial);
            if(bounds.isEmpty() || !area.activates(random)) { skippedAreas++; continue; }
            activeAreas++;
            for(int i=0,n=area.count(random);i<n;i++) ground.add(new GroundRequest(area,bounds.orElseThrow()));
        }
        task=scheduler.repeat(1,this::tick); return result;
    }
    private void tick() {
        try {
            if(cancelled) { if(pending==null || pending.isDone()) finish(new CancellationException("Loot generation cancelled")); return; }
            if(pending!=null) {
                if(!pending.isDone()) return;
                pending.join(); pending=null;
                for(var future:requested) {
                    Chunk chunk=future.join(); if(tickets.add(chunk.getChunkKey())) PaperChunkTickets.acquire(plugin,chunk.getWorld(),chunk.getX(),chunk.getZ()); sanitizer.ensure(chunk);
                    if(cancelled) return;
                }
                inspect.run(); releaseTickets(); return;
            }
            if(!points.isEmpty()) {
                ContainerLootPoint point=points.remove();
                // Adjacent physical chest half may cross a chunk edge. Sanitize it before combined inventory access.
                Set<Long> chunks=new LinkedHashSet<>();
                chunks.add(key(point.x()>>4,point.z()>>4));
                for(int[] offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) chunks.add(key((point.x()+offset[0])>>4,(point.z()+offset[1])>>4));
                request(chunks,()->fill(point)); return;
            }
            if(!ground.isEmpty()) {
                GroundRequest request=ground.peek(); var b=request.bounds;
                int x=random.nextInt(b.minX(),b.maxX()+1),z=random.nextInt(b.minZ(),b.maxZ()+1);
                request(Set.of(key(x>>4,z>>4)),()->drop(request,x,z)); return;
            }
            finish(null);
        } catch(RuntimeException error) { finish(error); }
    }
    private void request(Set<Long> chunks,Runnable inspect) {
        this.inspect=inspect; List<CompletableFuture<Chunk>> futures=new ArrayList<>();
        for(long key:chunks) futures.add(world().getChunkAtAsync((int)key,(int)(key>>32),true));
        requested=List.copyOf(futures); pending=CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
    private void fill(ContainerLootPoint point) {
        World world=world();
        if(point.y()<world.getMinHeight() || point.y()>=world.getMaxHeight()) { warn(point,"outside world height"); skippedPoints++; return; }
        BlockState state=world.getBlockAt(point.x(),point.y(),point.z()).getState();
        if(!(state instanceof InventoryHolder holder)) { warn(point,"not a container: " + state.getType()); skippedPoints++; return; }
        Inventory inventory=holder.getInventory();
        String identity=point.x()+":"+point.y()+":"+point.z();
        if(inventory.getHolder() instanceof DoubleChest chest) {
            Location left=Objects.requireNonNull(((InventoryHolder)chest.getLeftSide()).getInventory().getLocation());
            Location right=Objects.requireNonNull(((InventoryHolder)chest.getRightSide()).getInventory().getLocation());
            List<String> halves=new ArrayList<>(List.of(locationKey(left),locationKey(right))); Collections.sort(halves); identity=halves.toString();
        }
        if(!filled.add(identity)) { skippedPoints++; warn(point,"duplicate physical container skipped"); return; }
        if(state instanceof Lootable lootable) { lootable.setLootTable(null); state.update(true,false); }
        List<Integer> empty=new ArrayList<>();
        for(int i=0;i<inventory.getSize();i++) if(inventory.getItem(i)==null || inventory.getItem(i).getType().isAir()) empty.add(i);
        for(int i=empty.size()-1;i>0;i--) Collections.swap(empty,i,random.nextInt(i+1));
        List<ItemStack> stacks=stacks(point.table()); int n=Math.min(empty.size(),stacks.size());
        for(int i=0;i<n;i++) inventory.setItem(empty.get(i),stacks.get(i));
        if(n<stacks.size()) warn(point,"overflow: discarded " + (stacks.size()-n) + " stacks");
    }
    private List<ItemStack> stacks(String table) {
        List<ItemStack> result=new ArrayList<>();
        for(var roll:content.tables().get(table).roll(random)) {
            ItemStack prototype=items.resolve(roll.item());
            for(int amount:LootTable.split(roll.amount(),prototype.getMaxStackSize())) { ItemStack item=prototype.clone(); item.setAmount(amount); result.add(item); }
        }
        return result;
    }
    private void drop(GroundRequest request,int x,int z) {
        World world=world(); int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
        boolean safe=y>=request.area.minY() && y<=request.area.maxY() && y>world.getMinHeight() && y<world.getMaxHeight()
                && safeGround(world.getBlockAt(x,y-1,z),world.getBlockAt(x,y,z));
        if(safe) {
            for(ItemStack stack:stacks(request.area.table())) {
                if(groundItems>=10_000) throw new IllegalStateException("Ground loot entity budget exceeded (10000)");
                world.dropItem(new Location(world,x+.5,y+.1,z+.5),stack,item-> {
                    item.getPersistentDataContainer().set(marker,PersistentDataType.STRING,session.sessionId().toString());
                    item.setUnlimitedLifetime(true); item.setVelocity(new org.bukkit.util.Vector());
                }); groundItems++;
            }
            ground.remove();
        } else if(++request.attempts>=request.area.maxAttempts()) { ground.remove(); missedSpawns++; }
    }
    static boolean safeGround(Block floor,Block space) {
        return PaperSpawnTerrain.safeItemGround(floor,space);
    }
    private void warn(ContainerLootPoint point,String reason) {
        plugin.getLogger().warning("Loot map="+session.selectedMap().orElseThrow().id()+" point="+point.id()+" at="+point.x()+","+point.y()+","+point.z()+": "+reason);
    }
    private static String locationKey(Location l) { return l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }
    private static long key(int x,int z) { return ((long)z<<32)|(x&0xffffffffL); }
    private void releaseTickets() {
        World world=plugin.getServer().getWorld(worldId);
        if(world!=null) for(long key:tickets) PaperChunkTickets.release(plugin,world,(int)key,(int)(key>>32));
        tickets.clear();
    }
    private void finish(Throwable error) {
        if(result.isDone()) return;
        if(task!=null) task.cancel(); releaseTickets();
        state=error==null?State.COMPLETE:State.FAILED;
        if(error==null) result.complete(null); else result.completeExceptionally(error);
    }
    public CompletableFuture<Void> stop() {
        cancelled=true;
        if(pending==null || pending.isDone()) finish(new CancellationException("Loot stopped"));
        return result.handle((ignored,error)->null);
    }
    public void close() { cancelled=true; if(pending!=null) pending.cancel(false); finish(new CancellationException("Plugin stopped")); }
    public String diagnostics() {
        return "loot="+state+" active/skipped-points="+activePoints+"/"+skippedPoints+" active/skipped-areas="+activeAreas+"/"+skippedAreas
                +" ground-items="+groundItems+" missed-spawns="+missedSpawns+" sanitized-chunks="+sanitizer.chunks(worldId);
    }
    private static final class GroundRequest {
        final LootArea area; final LootArea.Bounds bounds; int attempts;
        GroundRequest(LootArea area,LootArea.Bounds bounds) { this.area=area; this.bounds=bounds; }
    }
}
