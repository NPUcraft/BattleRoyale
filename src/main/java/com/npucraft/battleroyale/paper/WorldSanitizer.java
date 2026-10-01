package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.loot.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.function.Consumer;

/** Only explicitly registered world UUIDs participate. No periodic sweeps or gamerule changes. */
public final class WorldSanitizer implements Listener,AutoCloseable {
    public static final int BLOCK_SAMPLES_PER_TICK=4096, BLOCK_WRITES_PER_TICK=256;
    private static final int MAX_QUEUED_CHUNKS=8192;
    private record Work(UUID world,long chunk){}
    private final Queue<Work> nativeQueue=new ArrayDeque<>();
    private final Set<Work> queued=new HashSet<>();
    private org.bukkit.scheduler.BukkitTask nativeTask;
    private final Map<Material,org.bukkit.block.data.BlockData> valuableData=new EnumMap<>(Material.class);
    private final Map<UUID,Registration> worlds=new HashMap<>();
    private final NamespacedKey groundLoot;
    private final NamespacedKey blocksSaved,entitiesSaved;
    public WorldSanitizer(NamespacedKey groundLoot) { this.groundLoot=groundLoot;
        blocksSaved=new NamespacedKey(groundLoot.getNamespace(),"sanitized_blocks_session");
        entitiesSaved=new NamespacedKey(groundLoot.getNamespace(),"sanitized_entities_session");
    }
    public WorldSanitizer(JavaPlugin plugin,NamespacedKey groundLoot){this(groundLoot);nativeTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1,1);}
    private NamespacedKey nativeKey(String suffix){return new NamespacedKey(groundLoot.getNamespace(),"native_blocks_"+suffix);}
    public void register(World world,UUID session,Consumer<Throwable> failed) {
        var registration=new Registration(session,failed);registration.nativeAware=true;
        if(worlds.putIfAbsent(world.getUID(),registration)!=null) throw new IllegalStateException("World already registered");
        world.getPersistentDataContainer().set(nativeKey("version1"),PersistentDataType.STRING,session.toString());
        for(Chunk chunk:world.getLoadedChunks()) ensure(chunk);
    }
    public void recover(World world,UUID session,Set<Long> blocks,Set<Long> entities,Consumer<Throwable> failed) {
        var registration=new Registration(session,failed);registration.recovered=true;registration.ledger.restore(blocks,entities);
        registration.nativeAware=session.toString().equals(world.getPersistentDataContainer().get(nativeKey("version1"),PersistentDataType.STRING));
        if(worlds.putIfAbsent(world.getUID(),registration)!=null)throw new IllegalStateException("World already registered");
        for(Chunk chunk:world.getLoadedChunks())ensure(chunk);
    }
    public Set<Long> blockKeys(UUID world){var r=worlds.get(world);return r==null?Set.of():r.ledger.blockKeys();}
    public Set<Long> entityKeys(UUID world){var r=worlds.get(world);return r==null?Set.of():r.ledger.entityKeys();}
    public boolean active(UUID world) { return worlds.containsKey(world); }
    public int chunks(UUID world) { Registration r=worlds.get(world); return r==null?0:r.ledger.chunks(); }
    public void remove(UUID world) { worlds.remove(world);nativeQueue.removeIf(work->work.world().equals(world));queued.removeIf(work->work.world().equals(world)); }
    /** The callback sees only registered, sanitized chunks and is detached by its owning loot runtime. */
    public void attachContainers(World world,Consumer<Chunk> listener){
        var registration=Objects.requireNonNull(worlds.get(world.getUID()),"Unregistered game world");
        if(registration.containers!=null&&registration.containers!=listener)throw new IllegalStateException("Container loot already attached");
        registration.containers=listener;
        for(var chunk:world.getLoadedChunks())ensure(chunk);
    }
    public void detachContainers(UUID world,Consumer<Chunk> listener){var registration=worlds.get(world);if(registration!=null&&registration.containers==listener)registration.containers=null;}
    public void ensure(Chunk chunk) {
        Registration registration=worlds.get(chunk.getWorld().getUID()); if(registration==null) throw new IllegalStateException("Unregistered game world");
        sanitizeBlocks(chunk,registration);
        // Force the entity list ready before plugin-generated items can be introduced.
        Entity[] entities=chunk.getEntities(); sanitizeEntities(chunk,Arrays.asList(entities),registration);
        if(registration.containers!=null)registration.containers.accept(chunk);
    }
    private void sanitizeBlocks(Chunk chunk,Registration registration) {
        nativeScan(chunk,registration);
        registration.ledger.blocks(chunk.getChunkKey(),()-> {
            if(registration.session.toString().equals(chunk.getPersistentDataContainer().get(blocksSaved,PersistentDataType.STRING)))return;
            // A recovered legacy chunk may contain player storage newer than its recovery ledger.
            // Never clear it speculatively. New matches persist the marker with the actual chunk.
            for(BlockState state:chunk.getTileEntities(false)) {
                if(state instanceof Lootable lootable) { lootable.setLootTable(null); state.update(true,false); }
                if(registration.recovered)continue;
                // Physical chest half only: never cross into a second, not-yet-sanitized chunk.
                if(state instanceof Chest chest) chest.getBlockInventory().clear();
                else if(state instanceof InventoryHolder holder) holder.getInventory().clear();
            }
            chunk.getPersistentDataContainer().set(blocksSaved,PersistentDataType.STRING,registration.session.toString());
        });
    }
    private void sanitizeEntities(Chunk chunk,List<Entity> entities,Registration registration) {
        registration.ledger.entities(chunk.getChunkKey(),()-> {
            if(registration.session.toString().equals(chunk.getPersistentDataContainer().get(entitiesSaved,PersistentDataType.STRING)))return;
            for(Entity entity:entities) {
                if(entity instanceof Player) continue;
                if(entity instanceof Lootable lootable) lootable.setLootTable(null);
                if(registration.recovered)continue;
                if(entity instanceof InventoryHolder holder) holder.getInventory().clear();
                if(entity instanceof Villager) continue;
                // Armor stands are not InventoryHolder: their gear lives on equipment slots, so a plain
                // inventory clear would leave a full kit wearable. Clear every slot, then remove it.
                if(entity instanceof ArmorStand stand){ stand.getEquipment().clear(); entity.remove(); continue; }
                if(StorageGuardPolicy.removedEntity(entity.getType().name())){ entity.remove(); continue; }
                if(entity instanceof Item && registration.session.toString().equals(entity.getPersistentDataContainer().get(groundLoot,PersistentDataType.STRING))) continue;
                if(entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof Mob) entity.remove();
            }
            chunk.getPersistentDataContainer().set(entitiesSaved,PersistentDataType.STRING,registration.session.toString());
        });
    }
    @EventHandler(priority=EventPriority.LOWEST) public void chunk(ChunkLoadEvent event) {
        Registration registration=worlds.get(event.getWorld().getUID()); if(registration==null) return;
        try {
            sanitizeBlocks(event.getChunk(),registration);
            if(event.getChunk().isEntitiesLoaded()) sanitizeEntities(event.getChunk(),Arrays.asList(event.getChunk().getEntities()),registration);
            if(registration.containers!=null)registration.containers.accept(event.getChunk());
        } catch(RuntimeException failure) { registration.failed.accept(failure); }
    }
    @EventHandler(priority=EventPriority.LOWEST) public void entities(EntitiesLoadEvent event) {
        Registration registration=worlds.get(event.getWorld().getUID()); if(registration==null) return;
        try { sanitizeEntities(event.getChunk(),event.getEntities(),registration); }
        catch(RuntimeException failure) { registration.failed.accept(failure); }
    }
    private NativeBlockScan nativeScan(Chunk chunk,Registration registration){
        if(!registration.nativeAware)return null;
        var cached=registration.nativeScans.get(chunk.getChunkKey());if(cached!=null)return cached;
        var pdc=chunk.getPersistentDataContainer();String session=registration.session.toString();
        NativeBlockScan scan;
        if(session.equals(pdc.get(nativeKey("session"),PersistentDataType.STRING))){
            Integer low=pdc.get(nativeKey("min_y"),PersistentDataType.INTEGER),high=pdc.get(nativeKey("max_y"),PersistentDataType.INTEGER),cursor=pdc.get(nativeKey("cursor"),PersistentDataType.INTEGER);
            if(low==null||high==null||cursor==null||low!=chunk.getWorld().getMinHeight()||high!=chunk.getWorld().getMaxHeight())throw new IllegalStateException("Invalid durable native block sanitation cursor");
            int[] excluded=pdc.get(nativeKey("excluded"),PersistentDataType.INTEGER_ARRAY);
            scan=new NativeBlockScan(low,high,cursor,excluded==null?new int[0]:excluded);
        }else{
            scan=new NativeBlockScan(chunk.getWorld().getMinHeight(),chunk.getWorld().getMaxHeight(),0,new int[0]);
            // Older active chunks lacking this feature's cursor cannot be distinguished from player changes.
            if(registration.recovered&&session.equals(pdc.get(blocksSaved,PersistentDataType.STRING)))scan.finish();
            else {
                if(valuableData.isEmpty())for(String name:ValuableBlockPolicy.STORAGE){var material=Material.valueOf(name);valuableData.put(material,material.createBlockData());}
                if(valuableData.values().stream().noneMatch(chunk::contains))scan.finish(); // Palette query; no full scan for ordinary terrain.
            }
            pdc.set(nativeKey("session"),PersistentDataType.STRING,session);saveScan(chunk,scan);
        }
        registration.nativeScans.put(chunk.getChunkKey(),scan);
        if(!scan.done()){
            Work work=new Work(chunk.getWorld().getUID(),chunk.getChunkKey());
            if(!queued.contains(work)){if(queued.size()>=MAX_QUEUED_CHUNKS)throw new IllegalStateException("Native block sanitation queue limit exceeded");queued.add(work);nativeQueue.add(work);}
        }
        return scan;
    }
    private void saveScan(Chunk chunk,NativeBlockScan scan){
        var pdc=chunk.getPersistentDataContainer();pdc.set(nativeKey("cursor"),PersistentDataType.INTEGER,scan.cursor());
        pdc.set(nativeKey("min_y"),PersistentDataType.INTEGER,scan.minY());pdc.set(nativeKey("max_y"),PersistentDataType.INTEGER,scan.maxY());
        int[] excluded=scan.exclusions();if(excluded.length==0)pdc.remove(nativeKey("excluded"));else pdc.set(nativeKey("excluded"),PersistentDataType.INTEGER_ARRAY,excluded);
    }
    /** Globally bounded, main-thread work. No chunks are loaded or pinned by this queue. */
    public void tick(){
        int samples=BLOCK_SAMPLES_PER_TICK,writes=BLOCK_WRITES_PER_TICK,turns=Math.min(nativeQueue.size(),64);
        while(samples>0&&writes>0&&turns-->0){
            Work work=nativeQueue.remove();queued.remove(work);Registration registration=worlds.get(work.world());
            World world=Bukkit.getWorld(work.world());if(registration==null||world==null)continue;
            if(!world.isChunkLoaded((int)work.chunk(),(int)(work.chunk()>>32))){registration.nativeScans.remove(work.chunk());continue;}
            Chunk chunk=world.getChunkAt((int)work.chunk(),(int)(work.chunk()>>32));
            try{
                NativeBlockScan scan=registration.nativeScans.get(work.chunk());if(scan==null)scan=nativeScan(chunk,registration);
                if(scan==null||scan.done())continue;
                // Generated structures and player placements may have appended durable exclusions since last tick.
                scan=reloadExclusions(chunk,scan);registration.nativeScans.put(work.chunk(),scan);
                final NativeBlockScan current=scan;
                var batch=scan.advance(samples,writes,index->replace(chunk.getBlock(current.x(index),current.y(index),current.z(index))));
                samples-=batch.visited();writes-=batch.replaced();saveScan(chunk,scan);
                if(!scan.done()&&queued.add(work))nativeQueue.add(work);
            }catch(RuntimeException failure){registration.failed.accept(failure);}
        }
    }
    private NativeBlockScan reloadExclusions(Chunk chunk,NativeBlockScan scan){
        var pdc=chunk.getPersistentDataContainer();Integer cursor=pdc.get(nativeKey("cursor"),PersistentDataType.INTEGER);
        int[] excluded=pdc.get(nativeKey("excluded"),PersistentDataType.INTEGER_ARRAY);
        return new NativeBlockScan(scan.minY(),scan.maxY(),cursor==null?scan.cursor():cursor,excluded==null?new int[0]:excluded);
    }
    private boolean replace(Block block){
        if(!ValuableBlockPolicy.replaces(block.getType().name()))return false;
        block.setType(Material.valueOf(ValuableBlockPolicy.replacement(block.getY())),false);return true;
    }
    private boolean originalValuable(Block block){
        Registration registration=worlds.get(block.getWorld().getUID());if(registration==null||!ValuableBlockPolicy.replaces(block.getType().name()))return false;
        NativeBlockScan scan=nativeScan(block.getChunk(),registration);if(scan==null)return false;
        scan=reloadExclusions(block.getChunk(),scan);return scan.original(scan.position(block.getX(),block.getY(),block.getZ()));
    }
    /** Generated structures bypass BlockPlaceEvent; persist their exclusion before writing any new block. */
    public static void preserveGenerated(JavaPlugin plugin,Block block){preserve(plugin.getName().toLowerCase(Locale.ROOT),block);}
    private static void preserve(String namespace,Block block){
        Chunk chunk=block.getChunk();var pdc=chunk.getPersistentDataContainer();
        var sessionKey=new NamespacedKey(namespace,"native_blocks_session");String session=pdc.get(sessionKey,PersistentDataType.STRING);
        if(session==null||!session.equals(block.getWorld().getPersistentDataContainer().get(new NamespacedKey(namespace,"native_blocks_version1"),PersistentDataType.STRING)))return;
        var cursorKey=new NamespacedKey(namespace,"native_blocks_cursor");var excludedKey=new NamespacedKey(namespace,"native_blocks_excluded");
        Integer low=pdc.get(new NamespacedKey(namespace,"native_blocks_min_y"),PersistentDataType.INTEGER),high=pdc.get(new NamespacedKey(namespace,"native_blocks_max_y"),PersistentDataType.INTEGER),cursor=pdc.get(cursorKey,PersistentDataType.INTEGER);
        if(low==null||high==null||cursor==null)throw new IllegalStateException("Missing native block sanitation state");
        int[] excluded=pdc.get(excludedKey,PersistentDataType.INTEGER_ARRAY);var scan=new NativeBlockScan(low,high,cursor,excluded==null?new int[0]:excluded);
        if(scan.done())return;scan.preserve(scan.position(block.getX(),block.getY(),block.getZ()));
        pdc.set(cursorKey,PersistentDataType.INTEGER,scan.cursor());int[] values=scan.exclusions();if(values.length==0)pdc.remove(excludedKey);else pdc.set(excludedKey,PersistentDataType.INTEGER_ARRAY,values);
    }
    public int nativeCursor(Chunk chunk){return chunk.getPersistentDataContainer().getOrDefault(nativeKey("cursor"),PersistentDataType.INTEGER,-1);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void placed(BlockPlaceEvent event){if(worlds.containsKey(event.getBlock().getWorld().getUID()))preserve(groundLoot.getNamespace(),event.getBlockPlaced());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void multiPlaced(BlockMultiPlaceEvent event){if(worlds.containsKey(event.getBlock().getWorld().getUID()))for(var state:event.getReplacedBlockStates())preserve(groundLoot.getNamespace(),state.getBlock());}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void nativeBreak(BlockBreakEvent event){if(originalValuable(event.getBlock())){event.setCancelled(true);event.setDropItems(false);replace(event.getBlock());}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void nativeExplosion(EntityExplodeEvent event){event.blockList().removeIf(block->{if(!originalValuable(block))return false;replace(block);return true;});}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void nativeBlockExplosion(BlockExplodeEvent event){event.blockList().removeIf(block->{if(!originalValuable(block))return false;replace(block);return true;});}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void nativePiston(BlockPistonExtendEvent event){if(event.getBlocks().stream().anyMatch(this::originalValuable))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void nativePull(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(this::originalValuable))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void movedPlayerBlocks(BlockPistonExtendEvent event){if(worlds.containsKey(event.getBlock().getWorld().getUID()))for(Block block:event.getBlocks())preserve(groundLoot.getNamespace(),block.getRelative(event.getDirection()));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void pulledPlayerBlocks(BlockPistonRetractEvent event){if(worlds.containsKey(event.getBlock().getWorld().getUID()))for(Block block:event.getBlocks()){preserve(groundLoot.getNamespace(),block.getRelative(event.getDirection()));preserve(groundLoot.getNamespace(),block.getRelative(event.getDirection().getOppositeFace()));}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void nativeEntityChange(EntityChangeBlockEvent event){if(originalValuable(event.getBlock()))event.setCancelled(true);}
    @EventHandler public void nativeUnload(ChunkUnloadEvent event){var registration=worlds.get(event.getWorld().getUID());if(registration!=null)registration.nativeScans.remove(event.getChunk().getChunkKey());}
    @Override public void close(){if(nativeTask!=null)nativeTask.cancel();HandlerList.unregisterAll(this);worlds.clear();nativeQueue.clear();queued.clear();}

    private static final class Registration {
        final UUID session; final Consumer<Throwable> failed; final SanitationLedger ledger=new SanitationLedger();
        boolean recovered,nativeAware;Consumer<Chunk> containers;
        final Map<Long,NativeBlockScan> nativeScans=new HashMap<>();
        Registration(UUID session,Consumer<Throwable> failed) { this.session=session; this.failed=failed; }
    }
}
