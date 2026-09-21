package com.lastsector.listener;
import com.lastsector.service.PluginRuntime;
import com.lastsector.combat.CombatTracker;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
/** Maps the surrogate's Paper events to the original participant UUID. */
public final class OfflineBodyListener implements Listener {
    private final PluginRuntime runtime;
    public OfflineBodyListener(PluginRuntime runtime){this.runtime=runtime;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void protect(EntityDamageEvent event){
        for(var entry:runtime.matches().combatEntries()){var body=entry.offline.entity(event.getEntity());if(body==null)continue;
            if(!body.active() || !event.getEntity().getUniqueId().equals(body.representation()) || entry.session.state()!=com.lastsector.session.GameState.RUNNING || runtime.matches().blocks(entry,runtime.provenance().attacker(event,entry),body.player()))event.setCancelled(true);return;}
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent event){
        for(var entry:runtime.matches().combatEntries()){var body=entry.offline.entity(event.getEntity());if(body==null || !body.active() || !event.getEntity().getUniqueId().equals(body.representation()))continue;
            var entity=(LivingEntity)event.getEntity();double absorbed=event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)?Math.min(entity.getAbsorptionAmount(),Math.max(0,-event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION))):0;
            var attacker=runtime.provenance().attacker(event,entry);entry.combat.tracker().record(body.player(),attacker,CombatTracker.effective(event.getFinalDamage(),entity.getHealth(),absorbed),runtime.provenance().origin(event.getDamageSource()),attacker!=null);return;}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void death(EntityDeathEvent event){
        for(var entry:runtime.matches().combatEntries()){var body=entry.offline.entity(event.getEntity());if(body==null)continue;event.getDrops().clear();event.setDroppedExp(0);if(event.getEntity().getUniqueId().equals(body.representation()))entry.offline.death(body,event.getDamageSource(),runtime.provenance());return;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEntityEvent event){for(var entry:runtime.matches().combatEntries())if(entry.offline.entity(event.getRightClicked())!=null){event.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void manipulate(PlayerArmorStandManipulateEvent event){for(var entry:runtime.matches().combatEntries())if(entry.offline.entity(event.getRightClicked())!=null){event.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent event){for(var entry:runtime.matches().combatEntries())if(entry.offline.entity(event.getEntity())!=null){event.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void transform(EntityTransformEvent event){for(var entry:runtime.matches().combatEntries()){var body=entry.offline.entity(event.getEntity());if(body!=null){event.setCancelled(true);entry.offline.eliminate(body,com.lastsector.combat.DamageOrigin.MOB,null);return;}}}
}
