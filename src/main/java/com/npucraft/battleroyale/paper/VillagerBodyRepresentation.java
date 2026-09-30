package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.offline.*;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
/** Verified public carrier: passive AI-disabled villager, plus a marker armor stand for equipment. */
public final class VillagerBodyRepresentation implements OfflineBodyRepresentation {
    private final JavaPlugin plugin;private final PaperBodySnapshots snapshots;private final NamespacedKey owner,session;
    private record Visual(UUID carrier,UUID equipment,int chunkX,int chunkZ,UUID world) {}
    private final Map<UUID,Visual> visuals=new HashMap<>();
    public VillagerBodyRepresentation(JavaPlugin plugin,PaperBodySnapshots snapshots){this.plugin=plugin;this.snapshots=snapshots;owner=new NamespacedKey(plugin,"offline_player");session=new NamespacedKey(plugin,"offline_session");}
    private void mark(Entity entity,OfflineBody body){RecoveryEntityCleaner.mark(entity);entity.getPersistentDataContainer().set(owner,PersistentDataType.STRING,body.player().toString());entity.getPersistentDataContainer().set(session,PersistentDataType.STRING,body.session().toString());entity.setPersistent(true);}
    @Override public UUID spawn(OfflineBody body) {
        Location at=PaperBodySnapshots.location(body.snapshot().position());World world=at.getWorld();List<Entity> made=new ArrayList<>();
        try {
            PaperChunkTickets.acquire(plugin,world,at.getBlockX()>>4,at.getBlockZ()>>4);
            Villager carrier=world.spawn(at,Villager.class,e->{mark(e,body);e.setAI(false);e.setAdult();e.setAgeLock(true);e.setSilent(true);e.setCanPickupItems(false);e.setRemoveWhenFarAway(false);e.setInvisible(true);e.getAttribute(Attribute.MAX_HEALTH).setBaseValue(body.snapshot().maxHealth());snapshots.equipment(e.getEquipment(),body.snapshot());snapshots.carrierVitals(e,body.snapshot());});made.add(carrier);
            ArmorStand equipment=world.spawn(at,ArmorStand.class,e->{mark(e,body);e.setMarker(true);e.setGravity(false);e.setInvulnerable(true);e.setArms(true);e.setBasePlate(false);e.customName(UiText.value(body.name()).append(I18n.shared("offline.label"," [暂时离线]"," [Disconnected]").color(UiText.WARNING)));e.setCustomNameVisible(true);snapshots.equipment(e.getEquipment(),body.snapshot());});made.add(equipment);
            visuals.put(body.player(),new Visual(carrier.getUniqueId(),equipment.getUniqueId(),at.getBlockX()>>4,at.getBlockZ()>>4,world.getUID()));return carrier.getUniqueId();
        } catch(RuntimeException failure){made.forEach(Entity::remove);PaperChunkTickets.release(plugin,world,at.getBlockX()>>4,at.getBlockZ()>>4);throw failure;}
    }
    public LivingEntity carrier(OfflineBody body){var visual=visuals.get(body.player());if(visual==null)return null;var entity=plugin.getServer().getEntity(visual.carrier());return entity instanceof LivingEntity living?living:null;}
    public boolean marked(Entity entity,OfflineBody body){return entityIds(body).contains(entity.getUniqueId()) && body.player().toString().equals(entity.getPersistentDataContainer().get(owner,PersistentDataType.STRING)) && body.session().toString().equals(entity.getPersistentDataContainer().get(session,PersistentDataType.STRING));}
    @Override public BodySnapshot capture(OfflineBody body) {
        var carrier=Objects.requireNonNull(carrier(body));var state=snapshots.carrier(carrier,body.snapshot());var visual=visuals.get(body.player());
        var equipment=plugin.getServer().getEntity(visual.equipment());if(equipment instanceof ArmorStand stand){stand.teleport(carrier.getLocation());snapshots.equipment(stand.getEquipment(),state);}
        // Pin the current chunk before releasing an obsolete one; bodies can be pushed/fall.
        int x=carrier.getLocation().getBlockX()>>4,z=carrier.getLocation().getBlockZ()>>4;
        if(x!=visual.chunkX() || z!=visual.chunkZ()){PaperChunkTickets.acquire(plugin,carrier.getWorld(),x,z);visuals.put(body.player(),new Visual(visual.carrier(),visual.equipment(),x,z,visual.world()));releaseUnused(visual);}
        return state;
    }
    @Override public boolean valid(OfflineBody body){var carrier=carrier(body);var visual=visuals.get(body.player());var equipment=visual==null?null:plugin.getServer().getEntity(visual.equipment());return carrier!=null && carrier.isValid() && !carrier.isDead() && equipment!=null && equipment.isValid();}
    @Override public void health(OfflineBody body,double health){Objects.requireNonNull(carrier(body)).setHealth(health);}
    private void releaseUnused(Visual visual){var world=plugin.getServer().getWorld(visual.world());if(world!=null)PaperChunkTickets.release(plugin,world,visual.chunkX(),visual.chunkZ());}
    @Override public void remove(OfflineBody body){var visual=visuals.remove(body.player());if(visual==null)return;for(UUID id:List.of(visual.carrier(),visual.equipment())){var e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}releaseUnused(visual);}
    @Override public Collection<UUID> entityIds(OfflineBody body){var visual=visuals.get(body.player());return visual==null?List.of():List.of(visual.carrier(),visual.equipment());}
}
