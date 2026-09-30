package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.loot.SanitationLedger;
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
public final class WorldSanitizer implements Listener {
    private final Map<UUID,Registration> worlds=new HashMap<>();
    private final NamespacedKey groundLoot;
    private final NamespacedKey blocksSaved,entitiesSaved;
    public WorldSanitizer(NamespacedKey groundLoot) { this.groundLoot=groundLoot;
        blocksSaved=new NamespacedKey(groundLoot.getNamespace(),"sanitized_blocks_session");
        entitiesSaved=new NamespacedKey(groundLoot.getNamespace(),"sanitized_entities_session");
    }
    public void register(World world,UUID session,Consumer<Throwable> failed) {
        if(worlds.putIfAbsent(world.getUID(),new Registration(session,failed))!=null) throw new IllegalStateException("World already registered");
        for(Chunk chunk:world.getLoadedChunks()) ensure(chunk);
    }
    public void recover(World world,UUID session,Set<Long> blocks,Set<Long> entities,Consumer<Throwable> failed) {
        var registration=new Registration(session,failed);registration.recovered=true;registration.ledger.restore(blocks,entities);
        if(worlds.putIfAbsent(world.getUID(),registration)!=null)throw new IllegalStateException("World already registered");
        for(Chunk chunk:world.getLoadedChunks())ensure(chunk);
    }
    public Set<Long> blockKeys(UUID world){var r=worlds.get(world);return r==null?Set.of():r.ledger.blockKeys();}
    public Set<Long> entityKeys(UUID world){var r=worlds.get(world);return r==null?Set.of():r.ledger.entityKeys();}
    public boolean active(UUID world) { return worlds.containsKey(world); }
    public int chunks(UUID world) { Registration r=worlds.get(world); return r==null?0:r.ledger.chunks(); }
    public void remove(UUID world) { worlds.remove(world); }
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
    private static final class Registration {
        final UUID session; final Consumer<Throwable> failed; final SanitationLedger ledger=new SanitationLedger();
        boolean recovered;Consumer<Chunk> containers;
        Registration(UUID session,Consumer<Throwable> failed) { this.session=session; this.failed=failed; }
    }
}
