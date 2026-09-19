package com.lastsector.paper;

import com.lastsector.loot.SanitationLedger;
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
    public WorldSanitizer(NamespacedKey groundLoot) { this.groundLoot=groundLoot; }
    public void register(World world,UUID session,Consumer<Throwable> failed) {
        if(worlds.putIfAbsent(world.getUID(),new Registration(session,failed))!=null) throw new IllegalStateException("World already registered");
        for(Chunk chunk:world.getLoadedChunks()) ensure(chunk);
    }
    public boolean active(UUID world) { return worlds.containsKey(world); }
    public int chunks(UUID world) { Registration r=worlds.get(world); return r==null?0:r.ledger.chunks(); }
    public void remove(UUID world) { worlds.remove(world); }
    public void ensure(Chunk chunk) {
        Registration registration=worlds.get(chunk.getWorld().getUID()); if(registration==null) throw new IllegalStateException("Unregistered game world");
        sanitizeBlocks(chunk,registration);
        // Force the entity list ready before plugin-generated items can be introduced.
        Entity[] entities=chunk.getEntities(); sanitizeEntities(chunk,Arrays.asList(entities),registration);
    }
    private void sanitizeBlocks(Chunk chunk,Registration registration) {
        registration.ledger.blocks(chunk.getChunkKey(),()-> {
            for(BlockState state:chunk.getTileEntities(false)) {
                if(state instanceof Lootable lootable) { lootable.setLootTable(null); state.update(true,false); }
                // Physical chest half only: never cross into a second, not-yet-sanitized chunk.
                if(state instanceof Chest chest) chest.getBlockInventory().clear();
                else if(state instanceof InventoryHolder holder) holder.getInventory().clear();
            }
        });
    }
    private void sanitizeEntities(Chunk chunk,List<Entity> entities,Registration registration) {
        registration.ledger.entities(chunk.getChunkKey(),()-> {
            for(Entity entity:entities) {
                if(entity instanceof Player) continue;
                if(entity instanceof Lootable lootable) lootable.setLootTable(null);
                if(entity instanceof InventoryHolder holder) holder.getInventory().clear();
                if(entity instanceof Villager) continue;
                if(entity instanceof Item && registration.session.toString().equals(entity.getPersistentDataContainer().get(groundLoot,PersistentDataType.STRING))) continue;
                if(entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof Mob) entity.remove();
            }
        });
    }
    @EventHandler(priority=EventPriority.LOWEST) public void chunk(ChunkLoadEvent event) {
        Registration registration=worlds.get(event.getWorld().getUID()); if(registration==null) return;
        try {
            sanitizeBlocks(event.getChunk(),registration);
            if(event.getChunk().isEntitiesLoaded()) sanitizeEntities(event.getChunk(),Arrays.asList(event.getChunk().getEntities()),registration);
        } catch(RuntimeException failure) { registration.failed.accept(failure); }
    }
    @EventHandler(priority=EventPriority.LOWEST) public void entities(EntitiesLoadEvent event) {
        Registration registration=worlds.get(event.getWorld().getUID()); if(registration==null) return;
        try { sanitizeEntities(event.getChunk(),event.getEntities(),registration); }
        catch(RuntimeException failure) { registration.failed.accept(failure); }
    }
    private static final class Registration {
        final UUID session; final Consumer<Throwable> failed; final SanitationLedger ledger=new SanitationLedger();
        Registration(UUID session,Consumer<Throwable> failed) { this.session=session; this.failed=failed; }
    }
}
