package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.MatchContentLoader;
import com.npucraft.battleroyale.loadout.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
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
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->new Thread(r,"BattleRoyale-loadout-io"));
    private final org.bukkit.scheduler.BukkitTask pump;
    private CompletableFuture<Void> saving;
    private Map<String,LoadoutDefinition> candidate;
    private View savingView;
    public LoadoutEditor(JavaPlugin plugin,NativeItemSerializer serializer) {
        this.plugin=plugin; this.serializer=serializer; pump=plugin.getServer().getScheduler().runTaskTimer(plugin,this::complete,1,1);
    }
    public boolean busy() { return !locks.isEmpty() || saving!=null; }
    public void replace(Map<String,LoadoutDefinition> definitions) {
        if(busy()) throw new IllegalStateException("请关闭装备编辑器并等待保存完成后再重载。");
        this.definitions=Map.copyOf(definitions);
    }
    public LoadoutDefinition definition(String id) {
        return Objects.requireNonNull(definitions.get(id),"装备方案不存在：" + id);
    }
    public void open(Player player,RoomDefinition room,List<RoomDefinition> rooms) {
        if(views.containsKey(player.getUniqueId())) throw new IllegalStateException("请先关闭当前编辑器。");
        if(locks.containsKey(room.loadoutId())) throw new IllegalStateException("此装备方案正在编辑或保存：" + room.loadoutId());
        if(player.getItemOnCursor()!=null && !player.getItemOnCursor().getType().isAir()) throw new IllegalStateException("请先放回鼠标光标上的物品，再打开编辑器。");
        View view=new View(player,new EditorDraft(definition(room.loadoutId())));
        locks.put(room.loadoutId(),player.getUniqueId()); views.put(player.getUniqueId(),view);
        render(view); player.openInventory(view.inventory);
        player.sendMessage(UiText.text(player,"共享装备方案 %s，使用此方案的房间：%s。左键自己的物品选择复制内容；左键目标格填入，右键清空。上方 0–35 格为背包，36–40 格依次为头盔/胸甲/护腿/靴子/副手。关闭或取消将放弃草稿。","Shared loadout %s, used by rooms: %s. Left-click an item in your inventory to copy it; left-click a target slot to insert it, or right-click to clear. Slots 0–35 are inventory; 36–40 are helmet/chestplate/leggings/boots/offhand. Closing or cancelling discards the draft.",room.loadoutId(),rooms.stream().filter(r->r.loadoutId().equals(room.loadoutId())).map(RoomDefinition::id).toList()));
    }
    private void render(View view) {
        view.inventory.clear(); var definition=view.draft.snapshot();
        for(int raw=0;raw<41;raw++) view.inventory.setItem(raw,serializer.item(definition.slots().get(LoadoutSlotMapping.inventorySlot(raw))));
        Player player=plugin.getServer().getPlayer(view.owner);
        view.inventory.setItem(45,button(Material.COMPASS,I18n.text(player,"选中快捷栏：%s（点击切换）","Selected hotbar slot: %s (click to change)",definition.selectedHotbarSlot())));
        view.inventory.setItem(49,button(Material.LIME_CONCRETE,I18n.text(player,"保存共享装备方案","Save shared loadout")));
        view.inventory.setItem(53,button(Material.RED_CONCRETE,I18n.text(player,"取消","Cancel")));
    }
    private ItemStack button(Material material,String name) {
        ItemStack item=new ItemStack(material); item.editMeta(meta->meta.displayName(material==Material.RED_CONCRETE?UiText.warning(name):material==Material.LIME_CONCRETE?UiText.success(name):UiText.heading(name))); return item;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        event.setCancelled(true);
        if(!view.owner.equals(event.getWhoClicked().getUniqueId()) || view.saving || !event.getWhoClicked().hasPermission("battleroyale.admin")) return;
        if(event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT) return;
        try {
            if(event.getClickedInventory()==event.getView().getBottomInventory()) {
                if(event.getClick()==ClickType.LEFT) view.draft.brush(serializer.store(event.getCurrentItem())); return;
            }
            int raw=event.getRawSlot();
            if(raw==LoadoutSlotMapping.CANCEL) event.getWhoClicked().closeInventory();
            else if(raw==LoadoutSlotMapping.SAVE) save(view);
            else { if(raw==LoadoutSlotMapping.HOTBAR) view.draft.nextHotbar(); else view.draft.click(raw,event.getClick()==ClickType.RIGHT); render(view); }
        } catch(RuntimeException error) { event.getWhoClicked().sendMessage(UiText.error(event.getWhoClicked(),"装备编辑失败：%s","Loadout edit failed: %s",I18n.error(event.getWhoClicked(),error.getMessage()))); }
    }
    private void save(View view) {
        if(saving!=null) throw new IllegalStateException("另一项保存正在进行，请稍后重试。");
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
        View view=savingView; String failure=null;
        try { saving.join(); definitions=candidate; }
        catch(RuntimeException error) { failure=error.getMessage(); plugin.getLogger().warning("Loadout save failed; previous definition retained: "+failure); }
        saving=null; candidate=null; savingView=null; view.saving=false;
        Player player=plugin.getServer().getPlayer(view.owner);
        if(player!=null) { player.sendMessage(failure==null?UiText.success(player,"装备方案已保存，将用于之后开始的比赛。","Loadout saved. It will be used by future matches."):UiText.error(player,"装备保存失败，已保留原方案：%s","Loadout save failed. The previous definition was retained: %s",I18n.error(player,failure))); if(player.getOpenInventory().getTopInventory().getHolder()==view) player.closeInventory(); }
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
        View(Player player,EditorDraft draft) { this.owner=player.getUniqueId(); this.draft=draft; inventory=Bukkit.createInventory(this,54,UiText.heading(player,"装备编辑：%s","Loadout editor: %s",draft.snapshot().id())); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
