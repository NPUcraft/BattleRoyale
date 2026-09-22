package com.npucraft.lastsector.paper;

import com.npucraft.lastsector.config.MatchContentLoader;
import com.npucraft.lastsector.loadout.*;
import com.npucraft.lastsector.room.RoomDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

/** Holder-identity virtual editor with per-loadout locks and serialized atomic disk publication. */
public final class LoadoutEditor implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final NativeItemSerializer serializer;
    private final Map<UUID,View> views=new HashMap<>();
    private final Map<String,UUID> locks=new HashMap<>();
    private Map<String,LoadoutDefinition> definitions=Map.of();
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->new Thread(r,"LastSector-loadout-io"));
    private final org.bukkit.scheduler.BukkitTask pump;
    private CompletableFuture<Void> saving;
    private Map<String,LoadoutDefinition> candidate;
    private View savingView;
    public LoadoutEditor(JavaPlugin plugin,NativeItemSerializer serializer) {
        this.plugin=plugin; this.serializer=serializer; pump=plugin.getServer().getScheduler().runTaskTimer(plugin,this::complete,1,1);
    }
    public boolean busy() { return !locks.isEmpty() || saving!=null; }
    public void replace(Map<String,LoadoutDefinition> definitions) {
        if(busy()) throw new IllegalStateException("Close loadout editors and wait for saves before reload");
        this.definitions=Map.copyOf(definitions);
    }
    public LoadoutDefinition definition(String id) {
        return Objects.requireNonNull(definitions.get(id),"Unknown loadout: " + id);
    }
    public void open(Player player,RoomDefinition room,List<RoomDefinition> rooms) {
        if(views.containsKey(player.getUniqueId())) throw new IllegalStateException("Close your current editor first");
        if(locks.containsKey(room.loadoutId())) throw new IllegalStateException("Loadout is being edited/saved: " + room.loadoutId());
        if(player.getItemOnCursor()!=null && !player.getItemOnCursor().getType().isAir()) throw new IllegalStateException("Put the cursor item back before editing");
        View view=new View(player.getUniqueId(),new EditorDraft(definition(room.loadoutId())));
        locks.put(room.loadoutId(),player.getUniqueId()); views.put(player.getUniqueId(),view);
        render(view); player.openInventory(view.inventory);
        player.sendMessage(Component.text("Shared loadout " + room.loadoutId() + " used by: " + rooms.stream().filter(r->r.loadoutId().equals(room.loadoutId())).map(RoomDefinition::id).toList()
                + ". Left-click your inventory to select a brush; left-click a target to paint, right-click to clear. Top slots 0-35: storage; 36-40: helmet/chestplate/leggings/boots/offhand. Close/Cancel discards."));
    }
    private void render(View view) {
        view.inventory.clear(); var definition=view.draft.snapshot();
        for(int raw=0;raw<41;raw++) view.inventory.setItem(raw,serializer.item(definition.slots().get(LoadoutSlotMapping.inventorySlot(raw))));
        view.inventory.setItem(45,button(Material.COMPASS,"Selected hotbar: " + definition.selectedHotbarSlot() + " (click to cycle)"));
        view.inventory.setItem(49,button(Material.LIME_CONCRETE,"Save shared loadout"));
        view.inventory.setItem(53,button(Material.RED_CONCRETE,"Cancel"));
    }
    private ItemStack button(Material material,String name) {
        ItemStack item=new ItemStack(material); item.editMeta(meta->meta.displayName(Component.text(name))); return item;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        event.setCancelled(true);
        if(!view.owner.equals(event.getWhoClicked().getUniqueId()) || view.saving || !event.getWhoClicked().hasPermission("lastsector.admin")) return;
        if(event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT) return;
        try {
            if(event.getClickedInventory()==event.getView().getBottomInventory()) {
                if(event.getClick()==ClickType.LEFT) view.draft.brush(serializer.store(event.getCurrentItem())); return;
            }
            int raw=event.getRawSlot();
            if(raw==LoadoutSlotMapping.CANCEL) event.getWhoClicked().closeInventory();
            else if(raw==LoadoutSlotMapping.SAVE) save(view);
            else { if(raw==LoadoutSlotMapping.HOTBAR) view.draft.nextHotbar(); else view.draft.click(raw,event.getClick()==ClickType.RIGHT); render(view); }
        } catch(RuntimeException error) { event.getWhoClicked().sendMessage(Component.text("Loadout edit failed: " + error.getMessage())); }
    }
    private void save(View view) {
        if(saving!=null) throw new IllegalStateException("Another save is in progress; retry shortly");
        LoadoutDefinition snapshot=view.draft.snapshot(); snapshot.slots().values().forEach(serializer::item);
        Map<String,LoadoutDefinition> next=new HashMap<>(definitions); next.put(snapshot.id(),snapshot);
        String yaml=MatchContentLoader.loadoutYaml(next); candidate=Map.copyOf(next); savingView=view; view.saving=true;
        java.nio.file.Path target=plugin.getDataFolder().toPath().resolve("loadouts.yml");
        saving=CompletableFuture.runAsync(()-> {
            try { AtomicFile.replace(target,yaml); }
            catch(java.io.IOException error) { throw new CompletionException(error); }
        },writer);
    }
    private void complete() {
        if(saving==null || !saving.isDone()) return;
        View view=savingView; String message;
        try { saving.join(); definitions=candidate; message="Loadout saved; applies to future matches."; }
        catch(RuntimeException error) { message="Loadout save failed; original retained: " + error.getMessage(); plugin.getLogger().warning(message); }
        saving=null; candidate=null; savingView=null; view.saving=false;
        Player player=plugin.getServer().getPlayer(view.owner);
        if(player!=null) { player.sendMessage(Component.text(message)); if(player.getOpenInventory().getTopInventory().getHolder()==view) player.closeInventory(); }
        release(view);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent event) { if(views.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent event) { if(views.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void close(InventoryCloseEvent event) { if(event.getInventory().getHolder() instanceof View view && !view.saving) release(view); }
    @EventHandler public void quit(PlayerQuitEvent event) { View view=views.get(event.getPlayer().getUniqueId()); if(view!=null && !view.saving) release(view); }
    private void release(View view) { views.remove(view.owner,view); locks.remove(view.draft.snapshot().id(),view.owner); }
    @Override public void close() {
        for(View view:List.copyOf(views.values())) { Player p=plugin.getServer().getPlayer(view.owner); if(p!=null) p.closeInventory(); }
        writer.shutdown();
        try { if(!writer.awaitTermination(30,TimeUnit.SECONDS)) plugin.getLogger().warning("Loadout IO still draining at shutdown"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); }
        complete(); pump.cancel(); views.clear(); locks.clear();
    }
    private static final class View implements InventoryHolder {
        final UUID owner; final EditorDraft draft; final Inventory inventory; boolean saving;
        View(UUID owner,EditorDraft draft) { this.owner=owner; this.draft=draft; inventory=Bukkit.createInventory(this,54,Component.text("Loadout: " + draft.snapshot().id())); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
