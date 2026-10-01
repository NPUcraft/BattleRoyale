package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.zone.Zone;
import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Session-owned, main-thread discovery. Chunk/tile tombstones survive emptying and recovery. */
public final class PaperAutoContainerLoot implements Listener,AutoCloseable {
    private static final int MAX_QUEUED_CHUNKS=4096;
    private final JavaPlugin plugin;
    private final UUID worldId;
    private final String session;
    private final Zone initial;
    private final WorldSanitizer sanitizer;
    private final AutoContainerLootSettings settings;
    private final LootTable table;
    private final Map<String,LootTable> tables;
    private final PaperLootRegionQuality quality;
    private final NativeLootItems items;
    private final RandomGenerator random;
    private final NamespacedKey ledgerSession,ledgerEntries,processed,airdrop;
    private final Consumer<Chunk> loaded=this::enqueue;
    private final Queue<Long> chunks=new ArrayDeque<>();
    private final Set<Long> queued=new HashSet<>(),scanned=new HashSet<>();
    private final Map<Long,ContainerLootLedger> ledgers=new HashMap<>();
    private final Set<Position> configured=new HashSet<>();
    private final GameScheduler.Task task;
    private Cursor cursor;
    private boolean closed;
    private int filled,decided,skipped,overflow;

    private record Position(int x,int y,int z){
        long chunk(){return ((long)(z>>4)<<32)|((x>>4)&0xffffffffL);}
    }
    private static final class Cursor {
        final long key;final BlockState[] states;int index;boolean retry;
        Cursor(Chunk chunk){key=chunk.getChunkKey();states=chunk.getTileEntities(false);}
    }
    public PaperAutoContainerLoot(JavaPlugin plugin,World world,UUID session,Zone initial,WorldSanitizer sanitizer,
            AutoContainerLootSettings settings,Map<String,LootTable> tables,Collection<ContainerLootPoint> points,
            NativeLootItems items,RandomGenerator random,GameScheduler scheduler){
        this(plugin,world,session,initial,sanitizer,settings,tables,points,items,random,scheduler,null);
    }
    public PaperAutoContainerLoot(JavaPlugin plugin,World world,UUID session,Zone initial,WorldSanitizer sanitizer,
            AutoContainerLootSettings settings,Map<String,LootTable> tables,Collection<ContainerLootPoint> points,
            NativeLootItems items,RandomGenerator random,GameScheduler scheduler,PaperLootRegionQuality quality){
        this.quality=quality;this.tables=Map.copyOf(tables);
        this.plugin=plugin;worldId=world.getUID();this.session=session.toString();this.initial=initial;this.sanitizer=sanitizer;
        this.settings=settings;table=settings.resolvedTable(tables);this.items=items;this.random=random;
        ledgerSession=new NamespacedKey(plugin,"container_loot_session");ledgerEntries=new NamespacedKey(plugin,"container_loot_decisions");
        processed=new NamespacedKey(plugin,"container_loot_done");airdrop=new NamespacedKey(plugin,"airdrop");
        for(var point:points)configured.add(new Position(point.x(),point.y(),point.z()));
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=scheduler.repeat(1,this::tick);
        try{sanitizer.attachContainers(world,loaded);}catch(RuntimeException error){close();throw error;}
    }
    private World world(){return plugin.getServer().getWorld(worldId);}
    private boolean belongs(World world){return !closed&&sanitizer.active(worldId)&&world.getUID().equals(worldId);}
    private void enqueue(Chunk chunk){
        if(closed||scanned.contains(chunk.getChunkKey())||queued.size()>=MAX_QUEUED_CHUNKS)return;
        double minX=chunk.getX()*16.0,minZ=chunk.getZ()*16.0;
        if(minX+16<initial.minX()||minX>initial.maxX()||minZ+16<initial.minZ()||minZ>initial.maxZ())return;
        if(queued.add(chunk.getChunkKey()))chunks.add(chunk.getChunkKey());
    }
    private void tick(){
        if(closed)return;
        World world=world();if(world==null||!sanitizer.active(worldId)){close();return;}
        try{
            // One tile-state array per tick; individual states consume the configured work budget.
            if(cursor==null){
                Long key=chunks.poll();if(key==null)return;
                if(!world.isChunkLoaded((int)(long)key,(int)(key>>32))){queued.remove(key);return;}
                cursor=new Cursor(world.getChunkAt((int)(long)key,(int)(key>>32)));
            }
            if(!world.isChunkLoaded((int)cursor.key,(int)(cursor.key>>32))){queued.remove(cursor.key);cursor=null;return;}
            int budget=settings.maxContainersPerTick();
            while(budget-->0&&cursor.index<cursor.states.length){
                var snapshot=cursor.states[cursor.index++];
                if(snapshot instanceof Container&&!process(snapshot.getBlock()))cursor.retry=true;
            }
            if(cursor.index>=cursor.states.length){
                long key=cursor.key;boolean retry=cursor.retry;cursor=null;queued.remove(key);
                if(retry)enqueue(world.getChunkAt((int)key,(int)(key>>32)));else scanned.add(key);
            }
        }catch(RuntimeException error){
            if(cursor!=null){queued.remove(cursor.key);scanned.add(cursor.key);cursor=null;}
            plugin.getLogger().log(java.util.logging.Level.WARNING,"自动容器物资生成失败；现有物品和处理标记已保留",error);
        }
    }
    /** false means a double chest's other chunk is not loaded yet; never force-load it. */
    private boolean process(Block block){
        if(!belongs(block.getWorld())||!initial.contains(block.getX()+.5,block.getZ()+.5))return true;
        if(!(block.getState() instanceof Container container)||!storage(container instanceof Chest chest?chest.getBlockInventory():container.getInventory()))return true;
        Position position=position(block);Position partner=partner(block);
        World world=block.getWorld();
        if(partner!=null&&!world.isChunkLoaded(partner.x()>>4,partner.z()>>4))return false;
        var halves=new ArrayList<Container>();halves.add(container);
        if(partner!=null&&world.getBlockAt(partner.x(),partner.y(),partner.z()).getState() instanceof Chest other)halves.add(other);
        // Mark both physical halves together, including losing the probability roll. A later split
        // must not make the untouched half look like a new single chest.
        boolean handled=false,excluded=false;
        for(var half:halves){
            var at=position(half.getBlock());
            handled|=ledger(half.getChunk()).contains(at.x(),at.y(),at.z())
                    ||session.equals(half.getPersistentDataContainer().get(processed,PersistentDataType.STRING));
            excluded|=configured.contains(at)||half.getPersistentDataContainer().has(airdrop)
                    ||!initial.contains(at.x()+.5,at.z()+.5);
        }
        if(handled){for(var half:halves)mark(half);return true;}
        for(var half:halves)mark(half);
        decided++;
        Inventory inventory=container.getInventory();
        // In particular, never clear or overwrite items a player stored before our bounded sweep.
        if(excluded||!inventory.isEmpty()||!settings.activates(random)){skipped++;return true;}
        var slots=new ArrayList<Integer>();for(int slot=0;slot<inventory.getSize();slot++)slots.add(slot);
        Collections.shuffle(slots,new Random(random.nextLong()));int slot=0;
        LootTable selected=quality==null?table:quality.resolve(block.getChunk(),table,tables);
        for(var roll:selected.roll(random)){
            ItemStack prototype=items.roll(roll.item(),random);
            for(int amount:LootTable.split(roll.amount(),prototype.getMaxStackSize())){
                if(slot>=slots.size()){overflow++;continue;}
                var stack=prototype.clone();stack.setAmount(amount);inventory.setItem(slots.get(slot++),stack);
            }
        }
        if(slot>0)filled++;return true;
    }
    private static boolean storage(Inventory inventory){return switch(inventory.getType()){
        case CHEST,BARREL,SHULKER_BOX,HOPPER,DISPENSER,DROPPER -> true;
        default -> false;
    };}
    private ContainerLootLedger ledger(Chunk chunk){return ledgers.computeIfAbsent(chunk.getChunkKey(),key->{
        var pdc=chunk.getPersistentDataContainer();
        long[] saved=session.equals(pdc.get(ledgerSession,PersistentDataType.STRING))?pdc.get(ledgerEntries,PersistentDataType.LONG_ARRAY):null;
        return new ContainerLootLedger(saved==null?new long[0]:saved);
    });}
    private void markPosition(World world,Position at){
        if(!world.isChunkLoaded(at.x()>>4,at.z()>>4))return;
        Chunk chunk=world.getChunkAt(at.x()>>4,at.z()>>4);var ledger=ledger(chunk);
        if(ledger.mark(at.x(),at.y(),at.z())){
            var pdc=chunk.getPersistentDataContainer();pdc.set(ledgerSession,PersistentDataType.STRING,session);
            pdc.set(ledgerEntries,PersistentDataType.LONG_ARRAY,ledger.snapshot());
        }
    }
    private void mark(Container container){
        markPosition(container.getWorld(),position(container.getBlock()));
        // Tile data also travels with shulker items. update(false,false) never restores a replaced block.
        if(!session.equals(container.getPersistentDataContainer().get(processed,PersistentDataType.STRING))){
            container.getPersistentDataContainer().set(processed,PersistentDataType.STRING,session);container.update(false,false);
        }
    }
    private void exclude(Block block){
        if(!belongs(block.getWorld()))return;
        markPosition(block.getWorld(),position(block));Position partner=partner(block);
        if(partner!=null)markPosition(block.getWorld(),partner);
        if(block.getState() instanceof Container container)mark(container);
    }
    private static Position position(Block block){return new Position(block.getX(),block.getY(),block.getZ());}
    private static Position partner(Block block){
        if(!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chest)
                ||chest.getType()==org.bukkit.block.data.type.Chest.Type.SINGLE)return null;
        var facing=chest.getFacing();int direction=chest.getType()==org.bukkit.block.data.type.Chest.Type.LEFT?1:-1;
        return new Position(block.getX()-facing.getModZ()*direction,block.getY(),block.getZ()+facing.getModX()*direction);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)public void opened(InventoryOpenEvent event){
        if(!(event.getPlayer() instanceof Player player)||!belongs(player.getWorld()))return;
        var holder=event.getInventory().getHolder();
        try{
            if(holder instanceof Container container)process(container.getBlock());
            else if(holder instanceof DoubleChest chest&&chest.getLeftSide() instanceof Container container)process(container.getBlock());
        }catch(RuntimeException error){plugin.getLogger().log(java.util.logging.Level.WARNING,"打开容器时无法生成物资；已保留现有物品",error);}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void placed(BlockPlaceEvent event){if(event.getBlockPlaced().getState() instanceof Container)exclude(event.getBlockPlaced());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void broken(BlockBreakEvent event){if(event.getBlock().getState() instanceof Container)exclude(event.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void dispensed(BlockDispenseEvent event){
        if(belongs(event.getBlock().getWorld())&&event.getItem().getType().name().endsWith("SHULKER_BOX")
                &&event.getBlock().getBlockData() instanceof Directional facing)exclude(event.getBlock().getRelative(facing.getFacing()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void pushed(BlockPistonExtendEvent event){
        if(belongs(event.getBlock().getWorld()))for(var block:event.getBlocks())if(block.getState() instanceof Container){exclude(block);exclude(block.getRelative(event.getDirection()));}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void pulled(BlockPistonRetractEvent event){
        if(belongs(event.getBlock().getWorld()))for(var block:event.getBlocks())if(block.getState() instanceof Container){exclude(block);exclude(block.getRelative(event.getDirection()));}
    }
    public String diagnostics(){return "automatic-containers="+filled+" decided="+decided+" skipped="+skipped+" overflow="+overflow+" queued-chunks="+queued.size();}
    @Override public void close(){if(closed)return;closed=true;task.cancel();HandlerList.unregisterAll(this);sanitizer.detachContainers(worldId,loaded);chunks.clear();queued.clear();scanned.clear();ledgers.clear();cursor=null;}
}
