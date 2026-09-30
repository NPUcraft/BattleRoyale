package com.npucraft.battleroyale.paper;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
/** Old visual/body carriers are not authority. Also handles entities loaded after bootstrap. */
public final class RecoveryEntityCleaner implements Listener {
    private static final String EPOCH=UUID.randomUUID().toString();private static final NamespacedKey KEY=new NamespacedKey("battleroyale","runtime_epoch");
    private final Set<UUID> worlds=new HashSet<>();
    public static void mark(Entity e){e.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,EPOCH);}
    public void register(World world){worlds.add(world.getUID());for(var chunk:world.getLoadedChunks())for(var entity:chunk.getEntities())clean(entity);}
    public void remove(UUID world){worlds.remove(world);}
    @EventHandler(priority=EventPriority.LOWEST) public void loaded(EntitiesLoadEvent event){if(worlds.contains(event.getWorld().getUID()))event.getEntities().forEach(this::clean);}
    @EventHandler public void unloaded(org.bukkit.event.world.WorldUnloadEvent event){remove(event.getWorld().getUID());}
    private void clean(Entity e){var p=e.getPersistentDataContainer();if(EPOCH.equals(p.get(KEY,PersistentDataType.STRING)))return;
        if(p.getKeys().stream().anyMatch(k->k.getNamespace().equals("battleroyale") && Set.of("deathbox_session","deathbox_id","offline_session","celebration_session").contains(k.getKey())))e.remove();}
}
