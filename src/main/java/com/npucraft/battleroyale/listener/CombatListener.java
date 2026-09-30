package com.npucraft.battleroyale.listener;
import com.npucraft.battleroyale.combat.*;
import com.npucraft.battleroyale.service.PluginRuntime;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;

/** Final, uncancelled public damage facts only. No kill decision is made inside a damage event. */
public final class CombatListener implements Listener {
    private final PluginRuntime runtime;
    public CombatListener(PluginRuntime runtime) { this.runtime=runtime; }
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player player)) return;
        runtime.matches().activePlayer(player.getUniqueId()).filter(e->e.combat!=null && e.inWorld(player.getWorld().getUID())).ifPresent(entry->{
            double absorbed=event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)
                    ?Math.min(player.getAbsorptionAmount(),Math.max(0,-event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION))):0;
            double amount=CombatTracker.effective(event.getFinalDamage(),player.getHealth(),absorbed);
            if(amount<=0) return;
            var attacker=runtime.provenance().attacker(event,entry);
            entry.combat.tracker().record(player.getUniqueId(),attacker,amount,runtime.provenance().origin(event.getDamageSource()),attacker!=null);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void endingDamage(EntityDamageEvent event) {
        if(event.getEntity() instanceof Player player && runtime.matches().endingPlayer(player.getUniqueId(),player.getWorld().getUID())) event.setCancelled(true);
        if(event instanceof EntityDamageByEntityEvent byEntity && runtime.celebrations().marked(byEntity.getDamager())) event.setCancelled(true);
    }
}
