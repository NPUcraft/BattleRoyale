package com.lastsector.paper;
import com.lastsector.death.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
/** Three static entities per box; no terrain changes or per-box scheduled task. */
public final class PaperDeathBoxVisuals implements DeathBoxVisualFactory {
    private final JavaPlugin plugin;
    private final NamespacedKey boxKey,sessionKey;
    public PaperDeathBoxVisuals(JavaPlugin plugin) { this.plugin=plugin; boxKey=new NamespacedKey(plugin,"deathbox_id"); sessionKey=new NamespacedKey(plugin,"deathbox_session"); }
    @Override public List<UUID> spawn(DeathBox box,Component text) {return spawn(box,text,Material.BARREL);}
    public List<UUID> spawn(DeathBox box,Component text,Material skin) {
        World world=Objects.requireNonNull(plugin.getServer().getWorld(box.location().world()));
        Location at=new Location(world,box.location().x(),box.location().y(),box.location().z()); List<UUID> created=new ArrayList<>();
        try {
            BlockDisplay display=world.spawn(at.clone().add(-.5,0,-.5),BlockDisplay.class,e->{ e.setBlock(skin.createBlockData()); mark(e,box); }); created.add(display.getUniqueId());
            Interaction hitbox=world.spawn(at,Interaction.class,e->{e.setInteractionWidth(1.3f);e.setInteractionHeight(1.3f);e.setResponsive(true);mark(e,box);}); created.add(hitbox.getUniqueId());
            TextDisplay label=world.spawn(at.clone().add(0,1.65,0),TextDisplay.class,e->{e.text(text);e.setBillboard(Display.Billboard.CENTER);e.setLineWidth(250);mark(e,box);}); created.add(label.getUniqueId());
            return List.copyOf(created);
        } catch(RuntimeException failure) { remove(created); throw failure; }
    }
    private void mark(Entity entity,DeathBox box) {
        entity.setGravity(false); entity.setInvulnerable(true); entity.setPersistent(true); entity.setSilent(true);
        RecoveryEntityCleaner.mark(entity);entity.getPersistentDataContainer().set(boxKey,PersistentDataType.STRING,box.id().toString());
        entity.getPersistentDataContainer().set(sessionKey,PersistentDataType.STRING,box.sessionId().toString());
    }
    public boolean marked(Entity entity,DeathBox box) {
        return box.id().toString().equals(entity.getPersistentDataContainer().get(boxKey,PersistentDataType.STRING))
                && box.sessionId().toString().equals(entity.getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING));
    }
    @Override public void remove(Collection<UUID> ids) { for(UUID id:ids) { Entity e=plugin.getServer().getEntity(id); if(e!=null) e.remove(); } }
}
