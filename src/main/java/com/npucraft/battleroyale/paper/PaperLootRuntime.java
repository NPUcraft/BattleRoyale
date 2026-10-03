package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.session.GameSession;
import com.npucraft.battleroyale.zone.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.inventory.*;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.random.RandomGenerator;

/** Bounded parallel chunk requests and main-thread terrain inspections; one immutable plan per match. */
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
    private final GroundLootBudget groundBudget=new GroundLootBudget();
    private final GroundLootPipeline<GroundCandidate,Chunk> pipeline=new GroundLootPipeline<>(GroundLootBudget.MAX_IN_FLIGHT);
    private CompletableFuture<Void> draining;private Throwable terminalFailure;
    private int maximumInFlight;
    private record GroundCandidate(GroundRequest request,int x,int z) {}
    private boolean groundBudgetExhausted;
    private final Set<Long> tickets=new HashSet<>();
    private final Set<String> filled=new HashSet<>();
    private final CompletableFuture<Void> result=new CompletableFuture<>();
    private CompletableFuture<?> pending;
    private List<CompletableFuture<Chunk>> requested=List.of();
    private Runnable inspect;
    private GameScheduler.Task task;
    private State state=State.NOT_STARTED;
    private PaperAutoContainerLoot automatic;
    private PaperLootRegionQuality quality;
    private final PaperGroundSupplies supplies;
    private boolean sealing;
    private static final int REFILL_MAX_ATTEMPTS=16,REFILL_MAX_IN_FLIGHT=4;
    private final GroundLootStages groundStages;
    private final Queue<RefillRequest> refillQueue=new ArrayDeque<>();
    private final List<RefillCandidate> refillCandidates=new ArrayList<>();
    private Zone refillZone;private LootTable refillNatural,refillBuilt;private int refillNeeded,refillResolved;
    private record RefillRequest(LootTable natural,LootTable built,int points) {}
    private static final class RefillCandidate {
        final int x,z;int attempts;CompletableFuture<Chunk> chunk;
        RefillCandidate(int x,int z){this.x=x;this.z=z;}
    }
    private NamespacedKey automaticSessionKey(){return new NamespacedKey(plugin,"automatic_loot_session");}
    public State state(){return state;}
    private boolean cancelled;private long generationStarted;
    private int activePoints,skippedPoints,activeAreas,skippedAreas,groundPoints,missedSpawns,explicitCompleted,totalProgress;
    public PaperLootRuntime(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,MatchContent content,NativeLootItems items,
            RandomGenerator random,NamespacedKey marker,GameScheduler scheduler) {
        this(plugin,session,sanitizer,content,items,random,marker,scheduler,Runnable::run);
    }
    public PaperLootRuntime(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,MatchContent content,NativeLootItems items,
            RandomGenerator random,NamespacedKey marker,GameScheduler scheduler,Executor io) {
        this.plugin=plugin; this.session=session; this.sanitizer=sanitizer; this.content=content; this.items=items; this.random=random; this.marker=marker; this.scheduler=scheduler;
        worldId=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName())).getUID();
        supplies=new PaperGroundSupplies(plugin,session,content,items,marker,io);
        groundStages=new GroundLootStages(content.groundLoot());
    }
    private World world() { return Objects.requireNonNull(plugin.getServer().getWorld(worldId),"Match world unloaded"); }
    private void activateAutomatic(){
        if(automatic!=null||cancelled||!content.autoContainers().enabled())return;
        var map=session.selectedMap().orElseThrow();var metadata=map.metadata()==null?content.maps().get(map.id()):map.metadata().loot();
        automatic=new PaperAutoContainerLoot(plugin,world(),session.sessionId(),session.initialZone().orElseThrow(),sanitizer,
                content.autoContainers(),content.tables(),metadata.containers(),items,random,scheduler,quality);
        world().getPersistentDataContainer().set(automaticSessionKey(),PersistentDataType.STRING,session.sessionId().toString());
    }
    /** Continue discovery after recovery, without regenerating configured points or ground drops. */
    public void recoverAutomatic(){
        if(state!=State.NOT_STARTED)throw new IllegalStateException("Loot runtime already started");
        quality=new PaperLootRegionQuality(plugin,world(),session.sessionId(),content.regionQuality(),true);
        supplies.recover();
        // Legacy runtime worlds have no per-container decisions. Treat their contents as already
        // played rather than introducing another roll into previously emptied player storage.
        if(session.sessionId().toString().equals(world().getPersistentDataContainer().get(automaticSessionKey(),PersistentDataType.STRING)))activateAutomatic();
        state=State.COMPLETE;result.complete(null);
    }
    /** Linear, non-compounding ground-loot scale for the initial ring: 1.0 up to 4 players,
     *  +10% per extra player, capped at 2.0 from 14 players onward. */
    static double initialSpawnScale(int players){return players<=4?1.0:Math.min(2.0,1.0+(players-4)*0.1);}
    public CompletableFuture<Void> generate() {
        if(state!=State.NOT_STARTED) return result;
        state=State.GENERATING;generationStarted=System.nanoTime();
        quality=new PaperLootRegionQuality(plugin,world(),session.sessionId(),content.regionQuality(),false);
        var map=session.selectedMap().orElseThrow();var metadata=map.metadata()==null?content.maps().get(map.id()):map.metadata().loot(); var initial=session.initialZone().orElseThrow();
        double scale=initialSpawnScale(session.players().size());
        if(scale>1.0)plugin.getLogger().info("Initial ground loot scaled x"+String.format(java.util.Locale.ROOT,"%.1f",scale)+" for "+session.players().size()+" players");
        for(var point:metadata.containers()) {
            if(initial.contains(point.x(),point.z())) { points.add(point); activePoints++; } else skippedPoints++;
        }
        for(var area:metadata.areas()) {
            var bounds=area.intersection(initial);
            if(bounds.isEmpty() || !area.activates(random)) { skippedAreas++; continue; }
            activeAreas++;
            int count=(int)Math.round(area.count(random)*scale);
            for(var cell:GroundLootDistribution.strata(bounds.orElseThrow(),count,random)) {
                if(ground.size()>=MapLoot.MAX_GROUND_REQUESTS)throw new IllegalStateException("Ground candidate budget exceeded");
                ground.add(new GroundRequest(area,cell));
            }
        }
        totalProgress=points.size()+ground.size();task=scheduler.repeat(1,this::tick); return result;
    }
    private void tick() {
        try {
            if(cancelled) { if(draining==null||draining.isDone())finish(terminalFailure==null?new CancellationException("Loot generation cancelled"):terminalFailure); return; }
            if(pending!=null) {
                if(!pending.isDone()) return;
                pending.join(); pending=null;
                if(sealing){finish(null);return;}
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
                request(chunks,()->{fill(point);explicitCompleted++;}); return;
            }
            var tickBudget=new GroundLootBudget.Tick(System.nanoTime());
            while(pipeline.size()>0&&tickBudget.inspect(System.nanoTime())){
                var ready=pipeline.takeReady();if(ready.isEmpty())break;
                var value=ready.orElseThrow();Chunk chunk=value.future().join();
                try{
                    if(tickets.add(chunk.getChunkKey()))PaperChunkTickets.acquire(plugin,chunk.getWorld(),chunk.getX(),chunk.getZ());
                    sanitizer.ensure(chunk);if(cancelled)return;
                    var candidate=value.candidate();drop(candidate.request(),candidate.x(),candidate.z());
                }finally{releaseTickets();}
            }
            while(!ground.isEmpty()&&pipeline.available()&&tickBudget.request(System.nanoTime())){
                if(!groundBudget.request(System.nanoTime())){
                    groundBudgetExhausted=true;missedSpawns+=ground.size();ground.clear();
                    plugin.getLogger().info("Ground loot candidate budget reached; generated="+groundPoints+" skipped="+missedSpawns+" attempts="+groundBudget.attempts());break;
                }
                GroundRequest request=ground.remove();var bounds=request.bounds;
                int x=random.nextInt(bounds.minX(),bounds.maxX()+1),z=random.nextInt(bounds.minZ(),bounds.maxZ()+1);
                var candidate=new GroundCandidate(request,x,z);
                pipeline.submit(candidate,()->world().getChunkAtAsync(x>>4,z>>4,true));
                maximumInFlight=Math.max(maximumInFlight,pipeline.size());
            }
            if(ground.isEmpty()&&pipeline.size()==0){sealing=true;pending=supplies.seal();}
        } catch(RuntimeException error) { finish(error); }
    }
    private void request(Set<Long> chunks,Runnable inspect) {
        this.inspect=inspect; List<CompletableFuture<Chunk>> futures=new ArrayList<>();
        try{for(long key:chunks)futures.add(world().getChunkAtAsync((int)key,(int)(key>>32),true));}
        finally{requested=List.copyOf(futures);pending=CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));}
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
        List<ItemStack> stacks=stacks(quality.resolve(world.getChunkAt(point.x()>>4,point.z()>>4),content.tables().get(point.table()),content.tables())); int n=Math.min(empty.size(),stacks.size());
        for(int i=0;i<n;i++) inventory.setItem(empty.get(i),stacks.get(i));
        if(n<stacks.size()) warn(point,"overflow: discarded " + (stacks.size()-n) + " stacks");
    }
    private List<ItemStack> stacks(LootTable table) {
        List<ItemStack> result=new ArrayList<>();
        for(var roll:table.roll(random)) {
            ItemStack prototype=items.roll(roll.item(),random);
            for(int amount:LootTable.split(roll.amount(),prototype.getMaxStackSize())) { ItemStack item=prototype.clone(); item.setAmount(amount); result.add(item); }
        }
        return result;
    }
    private void drop(GroundRequest request,int x,int z) {
        World world=world(); int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
        boolean safe=y>=request.area.minY() && y<=request.area.maxY() && y>world.getMinHeight() && y<world.getMaxHeight()
                && safeGround(world.getBlockAt(x,y-1,z),world.getBlockAt(x,y,z));
        if(safe) {
            // Opening tier follows the terrain: natural ground keeps the low table, built ground earns the mid table.
            if(content.groundLoot().enabled()){
                var staged=content.groundLoot();
                LootTable table=content.tables().get(staged.initialTable());
                if(staged.builtInitialTable()!=null&&quality!=null&&quality.quality(world.getChunkAt(x>>4,z>>4))==LootRegionQuality.BUILT)
                    table=content.tables().get(staged.builtInitialTable());
                supplies.plan(x,y,z,Objects.requireNonNull(table,"Missing staged ground table"),random.nextLong());
            }
            else supplies.plan(x,y,z,quality.resolve(world.getChunkAt(x>>4,z>>4),content.tables().get(request.area.table()),content.tables()),random.nextLong());
            groundPoints++;
        } else if(++request.attempts>=request.area.maxAttempts()||groundBudgetExhausted) { missedSpawns++; }
        else ground.add(request);
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
    private void beginDrain(Throwable error){
        cancelled=true;if(terminalFailure==null)terminalFailure=error;ground.clear();points.clear();
        refillQueue.clear();refillCandidates.clear();refillNatural=null;refillBuilt=null;
        if(draining==null)draining=CompletableFuture.allOf(pipeline.stop(),pending==null?CompletableFuture.completedFuture(null):pending.handle((unused,failure)->null));
    }
    private void finish(Throwable error) {
        if(error!=null){beginDrain(error);if(!draining.isDone())return;}
        if(generationStarted!=0&&!result.isDone())com.npucraft.battleroyale.admin.PerformanceMetricsService.LIVE.record(com.npucraft.battleroyale.admin.PerformanceMetricsService.Timer.LOOT,System.nanoTime()-generationStarted);
        if(result.isDone()) return;
        if(task!=null) task.cancel(); releaseTickets();
        if(error==null)try{activateAutomatic();}catch(RuntimeException failure){error=failure;}
        state=error==null?State.COMPLETE:State.FAILED;
        if(error==null) result.complete(null); else result.completeExceptionally(error);
    }
    public CompletableFuture<Void> stop() {
        beginDrain(new CancellationException("Loot stopped"));
        if(automatic!=null){automatic.close();automatic=null;}
        if(quality!=null)quality.close();
        if(draining.isDone())finish(terminalFailure);
        return CompletableFuture.allOf(result.handle((ignored,error)->null),draining,supplies.stop());
    }
    /** Disable closes every mutation path synchronously; underlying chunk futures are never cancelled to fake a drain. */
    public void close() {
        beginDrain(new CancellationException("Plugin stopped"));supplies.close();if(quality!=null)quality.close();
        if(automatic!=null){automatic.close();automatic=null;}if(task!=null)task.cancel();releaseTickets();
        if(!result.isDone()){state=State.FAILED;result.completeExceptionally(terminalFailure);}
    }
    public void tickSupplies(){supplies.tick();}
    /** Driven by the match loop every tick; refills higher-tier ground points on stage changes. */
    public void tickZone(ZoneRuntime zone){
        if(state!=State.COMPLETE||!content.groundLoot().enabled()||cancelled)return;
        for(var refill:groundStages.onStage(zone.stageNumber())){
            var natural=content.tables().get(refill.table());
            if(natural==null){plugin.getLogger().warning("Ground loot refill table missing: "+refill.table());continue;}
            var built=refill.builtTable()==null?null:content.tables().get(refill.builtTable());
            if(refill.builtTable()!=null&&built==null){plugin.getLogger().warning("Ground loot refill built table missing: "+refill.builtTable());continue;}
            refillQueue.add(new RefillRequest(natural,built,refill.points()));
        }
        if(refillNatural==null&&refillBuilt==null&&!refillQueue.isEmpty())startRefill(zone);
        if(refillNatural!=null||refillBuilt!=null)advanceRefill();
    }
    private void startRefill(ZoneRuntime zone){
        var request=refillQueue.poll();if(request==null)return;
        refillNatural=request.natural();refillBuilt=request.built();refillNeeded=request.points();refillResolved=0;refillCandidates.clear();
        // Future safe zone when it still has area (the final continuation shrinks to zero), otherwise the current one.
        var next=zone.next();refillZone=next!=null&&next.halfSize()>0?next:zone.current();
        fillRefillWindow();
    }
    private void fillRefillWindow(){
        while(refillResolved+refillCandidates.size()<refillNeeded&&refillCandidates.size()<REFILL_MAX_IN_FLIGHT){
            var candidate=spawnRefillCandidate();
            // A degenerate (zero-size) zone abandons the batch instead of retrying forever.
            if(candidate==null){refillResolved=refillNeeded;break;}
            refillCandidates.add(candidate);
        }
    }
    private RefillCandidate spawnRefillCandidate(){
        var target=refillZone;if(target==null||target.halfSize()<=0)return null;
        double radius=Math.max(.01,target.halfSize()-Math.min(8,target.halfSize()*.15));
        int minX=(int)Math.ceil(target.centerX()-radius-.5),maxX=(int)Math.floor(target.centerX()+radius-.5);
        int minZ=(int)Math.ceil(target.centerZ()-radius-.5),maxZ=(int)Math.floor(target.centerZ()+radius-.5);
        if(minX>maxX||minZ>maxZ)return null;
        var candidate=new RefillCandidate(random.nextInt(minX,maxX+1),random.nextInt(minZ,maxZ+1));
        candidate.chunk=world().getChunkAtAsync(candidate.x>>4,candidate.z>>4,true);
        return candidate;
    }
    private void advanceRefill(){
        for(int i=refillCandidates.size()-1;i>=0;i--){
            var candidate=refillCandidates.get(i);
            if(!candidate.chunk.isDone())continue;
            refillCandidates.remove(i);boolean placed=false;
            try{
                Chunk chunk=candidate.chunk.join();
                if(tickets.add(chunk.getChunkKey()))PaperChunkTickets.acquire(plugin,chunk.getWorld(),chunk.getX(),chunk.getZ());
                sanitizer.ensure(chunk);
                if(!cancelled&&placeRefill(chunk,candidate.x,candidate.z)){placed=true;refillResolved++;}
            }catch(RuntimeException error){
                plugin.getLogger().warning("Ground refill chunk failed at "+candidate.x+","+candidate.z+": "+error.getMessage());
            }finally{releaseTickets();}
            if(cancelled)return;
            if(!placed){
                var retry=candidate.attempts+1<REFILL_MAX_ATTEMPTS?spawnRefillCandidate():null;
                if(retry!=null){retry.attempts=candidate.attempts+1;refillCandidates.add(retry);}else refillResolved++;
            }
        }
        if(refillResolved>=refillNeeded){refillNatural=null;refillBuilt=null;refillCandidates.clear();}
        else fillRefillWindow();
    }
    private boolean placeRefill(Chunk chunk,int x,int z){
        World world=world();int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
        if(y<=world.getMinHeight()||y>=world.getMaxHeight())return false;
        if(!safeGround(world.getBlockAt(x,y-1,z),world.getBlockAt(x,y,z)))return false;
        // Built terrain earns the refill's better table; unknown samples deliberately stay natural.
        LootTable table=refillBuilt!=null&&quality!=null&&quality.quality(chunk)==LootRegionQuality.BUILT?refillBuilt:refillNatural;
        supplies.append(x,y,z,table,random.nextLong());return true;
    }
    public int progressCompleted(){return Math.min(totalProgress,explicitCompleted+groundPoints+missedSpawns);}
    public int progressTotal(){return totalProgress;}
    public String diagnostics() {
        return "loot="+state+" active/skipped-points="+activePoints+"/"+skippedPoints+" active/skipped-areas="+activeAreas+"/"+skippedAreas
                +" ground-points="+groundPoints+" missed-spawns="+missedSpawns+" candidate-attempts="+groundBudget.attempts()+" candidate-budget-exhausted="+groundBudgetExhausted+" max-in-flight="+maximumInFlight+" in-flight="+pipeline.size()+" sanitized-chunks="+sanitizer.chunks(worldId)
                +" "+supplies.diagnostics()+" "+(quality==null?"region-quality=inactive":quality.diagnostics())
                +" "+(automatic==null?"automatic-containers=inactive":automatic.diagnostics());
    }
    private static final class GroundRequest {
        final LootArea area; final LootArea.Bounds bounds; int attempts;
        GroundRequest(LootArea area,LootArea.Bounds bounds) { this.area=area; this.bounds=bounds; }
    }
}
