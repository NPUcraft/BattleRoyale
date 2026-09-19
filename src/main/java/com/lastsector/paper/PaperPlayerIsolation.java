package com.lastsector.paper;

import com.lastsector.loadout.*;
import com.lastsector.player.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** All decoding finishes before restoration writes; original ender chest/cursor are isolated too. */
public final class PaperPlayerIsolation implements PlayerIsolation.Gateway<MatchPlayerSnapshot,LoadoutDefinition> {
    private final JavaPlugin plugin;
    private final NativeItemSerializer serializer;
    private String lobby;
    public PaperPlayerIsolation(JavaPlugin plugin,NativeItemSerializer serializer) { this.plugin=plugin; this.serializer=serializer; }
    public void lobby(String world) { lobby=world; }
    private Player player(UUID id) {
        Player player=plugin.getServer().getPlayer(id);
        if (player==null || !player.isOnline() || player.isDead()) throw new IllegalStateException("Player unavailable: " + id);
        return player;
    }
    private Map<Integer,StoredItem> store(ItemStack[] items) {
        Map<Integer,StoredItem> result=new HashMap<>();
        for(int i=0;i<items.length;i++) { StoredItem item=serializer.store(items[i]); if(item!=null) result.put(i,item); }
        return result;
    }
    private ItemStack[] decode(Map<Integer,StoredItem> items,int size) {
        ItemStack[] result=new ItemStack[size]; items.forEach((slot,item)->result[slot]=serializer.item(item)); return result;
    }
    @Override public MatchPlayerSnapshot capture(UUID id) {
        Player p=player(id);
        // Capture is read-only, including cursor: a later capture failure must not move/drop any item.
        return new MatchPlayerSnapshot(store(p.getInventory().getContents()),store(p.getEnderChest().getContents()),serializer.store(p.getItemOnCursor()),
                p.getInventory().getHeldItemSlot(),p.getLevel(),p.getExp(),p.getTotalExperience(),p.getGameMode(),p.getHealth(),p.getFoodLevel(),p.getSaturation(),p.getExhaustion(),
                List.copyOf(p.getActivePotionEffects()),p.getFireTicks(),p.getFallDistance(),p.getAbsorptionAmount(),p.getAllowFlight(),p.isFlying());
    }
    @Override public void apply(UUID id,LoadoutDefinition loadout) {
        Player p=player(id); ItemStack[] items=decode(loadout.slots(),41);
        p.setItemOnCursor(null); p.closeInventory(); p.getInventory().setContents(items); p.getEnderChest().clear();
        p.setGameMode(GameMode.SURVIVAL); p.setFlying(false); p.setAllowFlight(false);
        p.getActivePotionEffects().forEach(effect->p.removePotionEffect(effect.getType()));
        p.setHealth(maxHealth(p)); p.setAbsorptionAmount(0); p.setFoodLevel(20); p.setSaturation(5); p.setExhaustion(0);
        p.setExp(0); p.setLevel(0); p.setTotalExperience(0); p.getInventory().setHeldItemSlot(loadout.selectedHotbarSlot());
        p.setFireTicks(0); p.setFallDistance(0);
    }
    @Override public boolean restore(UUID id,MatchPlayerSnapshot s) {
        Player p=plugin.getServer().getPlayer(id); if(p==null || !p.isOnline() || p.isDead()) return false;
        ItemStack[] inventory=decode(s.inventory(),41), ender=decode(s.enderChest(),27); ItemStack cursor=serializer.item(s.cursor());
        p.setItemOnCursor(null); p.closeInventory(); p.getInventory().setContents(inventory); p.getEnderChest().setContents(ender);
        p.setGameMode(s.mode()); p.setAllowFlight(s.allowFlight()); p.setFlying(s.allowFlight() && s.flying());
        p.getActivePotionEffects().forEach(effect->p.removePotionEffect(effect.getType())); p.addPotionEffects(s.effects());
        p.setHealth(Math.max(.01,Math.min(s.health(),maxHealth(p)))); p.setAbsorptionAmount(s.absorption());
        p.setFoodLevel(s.food()); p.setSaturation(s.saturation()); p.setExhaustion(s.exhaustion());
        p.setLevel(s.level()); p.setExp(s.progress()); p.setTotalExperience(s.totalExperience()); p.getInventory().setHeldItemSlot(s.selected());
        World world=plugin.getServer().getWorld(lobby);
        // Retain the snapshot if teleport is rejected, so a later retry can finish the transaction.
        if(world==null || !p.teleport(world.getSpawnLocation())) return false;
        p.setFireTicks(s.fireTicks()); p.setFallDistance(s.fallDistance()); p.setItemOnCursor(cursor);
        return true;
    }
    private double maxHealth(Player p) { return Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue(); }
}
