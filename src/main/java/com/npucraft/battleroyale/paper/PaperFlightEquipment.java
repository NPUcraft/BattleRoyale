package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.flight.TemporaryChestSlot;
import com.npucraft.battleroyale.service.UiText;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** One temporary chest lease per player. No original item is embedded in the copyable elytra. */
public final class PaperFlightEquipment implements Listener,AutoCloseable {
    private record Lease(Player player,TemporaryChestSlot slot) {}
    private final JavaPlugin plugin;
    private final UUID session;
    private final NamespacedKey ownerKey,sessionKey;
    private final Map<UUID,Lease> leases=new LinkedHashMap<>();
    private final Map<UUID,Player> tracked=new LinkedHashMap<>();
    private final Consumer<Throwable> failure;
    private boolean closed;
    public PaperFlightEquipment(JavaPlugin plugin,UUID session,Consumer<Throwable> failure){
        this.plugin=plugin;this.session=session;this.failure=failure;
        ownerKey=new NamespacedKey(plugin,"flight_owner");sessionKey=new NamespacedKey(plugin,"flight_session");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public void equip(Player player){
        if(closed||leases.containsKey(player.getUniqueId()))throw new IllegalStateException("Flight equipment lease already present or closed");
        ItemStack original=player.getInventory().getChestplate();
        if(marked(original))throw new IllegalStateException("Cannot wrap existing temporary flight equipment");
        byte[] bytes=original==null||original.getType().isAir()?new byte[0]:original.serializeAsBytes();
        var slot=new TemporaryChestSlot(player.getUniqueId(),session,bytes);
        ItemStack item=create(plugin,player,session);
        leases.put(player.getUniqueId(),new Lease(player,slot));tracked.put(player.getUniqueId(),player);
        player.getInventory().setChestplate(item);
    }
    public static ItemStack create(JavaPlugin plugin,Player player,UUID session){
        ItemStack item=new ItemStack(Material.ELYTRA);
        var meta=item.getItemMeta();meta.setUnbreakable(true);
        meta.displayName(UiText.heading(player,"临时跳伞鞘翅","Temporary deployment elytra"));
        meta.lore(List.of(UiText.muted(player,"落地立即收回，恢复原胸甲","Removed on landing; your chest armor is restored")));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"flight_owner"),PersistentDataType.STRING,player.getUniqueId().toString());
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"flight_session"),PersistentDataType.STRING,session.toString());
        item.setItemMeta(meta);return item;
    }
    public boolean equipped(Player player){
        var item=player.getInventory().getChestplate();
        return leases.containsKey(player.getUniqueId())&&item!=null&&item.getType()==Material.ELYTRA&&item.getAmount()==1&&owned(item,player.getUniqueId());
    }
    public boolean active(UUID player){return leases.containsKey(player);}
    private boolean marked(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(sessionKey,PersistentDataType.STRING);}
    private boolean ours(ItemStack item){return marked(item)&&session.toString().equals(item.getItemMeta().getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING));}
    private boolean owned(ItemStack item,UUID owner){return ours(item)&&owner.toString().equals(item.getItemMeta().getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING));}
    private void purge(Player player){
        var inventory=player.getInventory();
        for(int i=0;i<inventory.getSize();i++)if(ours(inventory.getItem(i)))inventory.setItem(i,null);
        if(ours(player.getItemOnCursor()))player.setItemOnCursor(null);
    }
    /** Synchronous and once-only; call before any lobby snapshot restoration or offline-body capture. */
    public void restore(Player player){
        var lease=leases.remove(player.getUniqueId());
        if(lease==null){purge(player);return;}
        var original=lease.slot().release(player.getUniqueId(),session).orElseThrow();
        try{player.setGliding(false);player.setFallDistance(0);}
        finally{purge(player);player.getInventory().setChestplate(original.length==0?null:ItemStack.deserializeBytes(original));}
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void click(InventoryClickEvent event){
        if(leases.containsKey(event.getWhoClicked().getUniqueId())||ours(event.getCurrentItem())||ours(event.getCursor()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void drag(InventoryDragEvent event){
        if(leases.containsKey(event.getWhoClicked().getUniqueId())||ours(event.getOldCursor())||event.getNewItems().values().stream().anyMatch(this::ours))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void drop(PlayerDropItemEvent event){
        if(leases.containsKey(event.getPlayer().getUniqueId())||ours(event.getItemDrop().getItemStack()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void swap(PlayerSwapHandItemsEvent event){
        if(leases.containsKey(event.getPlayer().getUniqueId())||ours(event.getMainHandItem())||ours(event.getOffHandItem()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void interact(PlayerInteractEvent event){
        if(leases.containsKey(event.getPlayer().getUniqueId())||ours(event.getItem()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void damage(PlayerItemDamageEvent event){if(ours(event.getItem()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void move(InventoryMoveItemEvent event){if(ours(event.getItem()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void spawn(ItemSpawnEvent event){if(ours(event.getEntity().getItemStack()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void pickup(EntityPickupItemEvent event){if(ours(event.getItem().getItemStack()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void death(PlayerDeathEvent event){
        var player=event.getEntity();var lease=leases.remove(player.getUniqueId());if(lease==null)return;
        byte[] original=lease.slot().release(player.getUniqueId(),session).orElseThrow();
        event.getDrops().removeIf(this::ours);purge(player);
        ItemStack restored=original.length==0?null:ItemStack.deserializeBytes(original);
        if(event.getKeepInventory())player.getInventory().setChestplate(restored);
        else{player.getInventory().setChestplate(null);if(restored!=null)event.getDrops().add(restored);}
        failure.accept(new IllegalStateException("Participant died during flight deployment"));
    }
    @Override public void close(){
        if(closed)return;closed=true;HandlerList.unregisterAll(this);
        Throwable first=null;
        for(var player:List.copyOf(tracked.values()))try{restore(player);}catch(Throwable error){if(first==null)first=error;else first.addSuppressed(error);}
        leases.clear();tracked.clear();if(first!=null)throw new IllegalStateException("Could not restore flight equipment",first);
    }
}