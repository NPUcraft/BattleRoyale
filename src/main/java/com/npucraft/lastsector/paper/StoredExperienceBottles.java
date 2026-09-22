package com.npucraft.lastsector.paper;

import com.npucraft.lastsector.combat.ExperienceMath;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.*;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

/** Explicit PDC schema; ordinary experience bottles never enter this path. */
public final class StoredExperienceBottles implements Listener {
    private final NamespacedKey marker,amount,paid;
    public StoredExperienceBottles(JavaPlugin plugin) {
        marker=new NamespacedKey(plugin,"stored_xp_bottle"); amount=new NamespacedKey(plugin,"stored_xp"); paid=new NamespacedKey(plugin,"stored_xp_paid");
    }
    public ItemStack create(int xp) {
        if(!ExperienceMath.validBottle(xp)) throw new IllegalArgumentException("Invalid stored XP");
        ItemStack item=new ItemStack(Material.EXPERIENCE_BOTTLE);
        item.editMeta(meta->{
            meta.displayName(Component.text("Stored Experience")); meta.lore(List.of(Component.text(xp+" XP")));
            write(meta.getPersistentDataContainer(),xp);
        }); return item;
    }
    private void write(PersistentDataContainer data,int xp) {
        data.set(marker,PersistentDataType.BYTE,(byte)1); data.set(amount,PersistentDataType.INTEGER,xp);
    }
    public Integer read(PersistentDataContainer data) {
        if(!data.has(marker,PersistentDataType.BYTE) || !data.has(amount,PersistentDataType.INTEGER)) return null;
        if(!Byte.valueOf((byte)1).equals(data.get(marker,PersistentDataType.BYTE))) return null;
        Integer xp=data.get(amount,PersistentDataType.INTEGER); return ExperienceMath.validBottle(xp)?xp:null;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void validate(PlayerLaunchProjectileEvent event) {
        if(!(event.getProjectile() instanceof ThrownExpBottle) || !event.getItemStack().hasItemMeta()) return;
        var data=event.getItemStack().getItemMeta().getPersistentDataContainer();
        if(data.has(marker) && read(data)==null) event.setCancelled(true);
        else if(read(data)!=null) event.setShouldConsume(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void launch(PlayerLaunchProjectileEvent event) {
        if(!(event.getProjectile() instanceof ThrownExpBottle) || !event.getItemStack().hasItemMeta()) return;
        Integer xp=read(event.getItemStack().getItemMeta().getPersistentDataContainer());
        if(xp!=null) write(event.getProjectile().getPersistentDataContainer(),xp);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hit(ExpBottleEvent event) {
        var data=event.getEntity().getPersistentDataContainer(); if(!data.has(marker)) return;
        Integer xp=read(data);
        event.setExperience(xp==null || data.has(paid)?0:xp); data.set(paid,PersistentDataType.BYTE,(byte)1);
    }
}
