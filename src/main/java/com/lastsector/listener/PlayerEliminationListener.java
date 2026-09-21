package com.lastsector.listener;
import com.lastsector.combat.*;
import com.lastsector.death.*;
import com.lastsector.loadout.StoredItem;
import com.lastsector.paper.NativeItemSerializer;
import com.lastsector.service.PluginRuntime;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Captures the actual carried inventory, never a filtered vanilla drop list. */
public final class PlayerEliminationListener implements Listener {
    private final PluginRuntime runtime;
    private final NativeItemSerializer serializer;
    public PlayerEliminationListener(PluginRuntime runtime,NativeItemSerializer serializer) { this.runtime=runtime;this.serializer=serializer; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void death(PlayerDeathEvent event) {
        var player=event.getPlayer();
        var entry=runtime.matches().activePlayer(player.getUniqueId()).filter(e->e.combat!=null && e.inWorld(player.getWorld().getUID())).orElse(null);
        if(entry==null) return;
        try {
            List<StoredItem> contents=new ArrayList<>();
            for(ItemStack item:player.getInventory().getStorageContents()) add(contents,item);
            add(contents,player.getInventory().getItemInOffHand());
            for(ItemStack item:player.getInventory().getArmorContents()) add(contents,item);
            add(contents,player.getItemOnCursor());
            int total=ExperienceMath.total(player.getLevel(),player.getExp());
            var location=player.getLocation(); var position=new DeathPosition(player.getWorld().getUID(),location.getX(),location.getY(),location.getZ())
                    .visible(player.getWorld().getMinHeight(),player.getWorld().getMaxHeight());
            boolean zone=entry.combat.isZone(player.getUniqueId());
            var cause=zone?DamageOrigin.ZONE:runtime.provenance().origin(event.getDamageSource());
            UUID attacker=zone?null:runtime.provenance().attacker(event.getDamageSource(),entry);
            suppress(event);
            entry.combat.eliminate(new EliminationRequest(player.getUniqueId(),player.getName(),position,cause,attacker,contents,total,entry.combat.now(),Bukkit.getCurrentTick()));
        } catch(RuntimeException failure) {
            suppress(event); runtime.deferRestore(entry.session.sessionId(),player.getUniqueId()); entry.combat.fail(failure);
        }
    }
    private void add(List<StoredItem> items,ItemStack item) { StoredItem stored=serializer.store(item==null?null:item.clone());if(stored!=null) items.add(stored); }
    private void suppress(PlayerDeathEvent event) {
        event.getDrops().clear();event.getItemsToKeep().clear();event.setKeepInventory(false);event.setKeepLevel(false);
        event.setDroppedExp(0);event.setShouldDropExperience(false);event.setNewExp(0);event.setNewLevel(0);event.setNewTotalExp(0);
        event.deathMessage(null);event.setShowDeathMessages(false);
        var player=event.getPlayer();player.setItemOnCursor(null);player.getInventory().clear();player.setExp(0);player.setLevel(0);player.setTotalExperience(0);
    }
}
