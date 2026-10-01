package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.*;
import com.npucraft.battleroyale.session.GameSession;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Owner/session-bound voting inventory; stale clicks can never vote in a subsequent room. */
public final class PaperRegionVoteMenu implements Listener {
    private final JavaPlugin plugin;
    private final java.util.function.Supplier<RoomRuntimeService> rooms;
    private final java.util.function.Predicate<Player> unavailable;
    public PaperRegionVoteMenu(JavaPlugin plugin,PluginRuntime runtime){
        this(plugin,runtime::rooms,player->!runtime.recoveryReady()||runtime.pendingRestore(player.getUniqueId())||runtime.matches().frozen(player.getUniqueId()));
    }
    public PaperRegionVoteMenu(JavaPlugin plugin,RoomRuntimeService rooms,java.util.function.Predicate<Player> unavailable){
        this(plugin,()->Objects.requireNonNull(rooms),unavailable);
    }
    public PaperRegionVoteMenu(JavaPlugin plugin,java.util.function.Supplier<RoomRuntimeService> rooms,java.util.function.Predicate<Player> unavailable){
        this.plugin=Objects.requireNonNull(plugin);this.rooms=Objects.requireNonNull(rooms);this.unavailable=Objects.requireNonNull(unavailable);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private GameSession require(Player player){
        if(!player.hasPermission("battleroyale.play")||player.isDead()||unavailable.test(player))
            throw new IllegalStateException(I18n.text(player,"现在无法进行区域投票。","Region voting is unavailable right now."));
        var session=rooms.get().participant(player.getUniqueId()).orElseThrow(()->new IllegalStateException(I18n.text(player,"请先加入一个房间。","Join a room first.")));
        if(!session.joinable())throw new IllegalStateException(I18n.text(player,"区域投票已结束。","Region voting has closed."));
        return session;
    }
    public void open(Player player){var session=require(player);var menu=new Menu(player,session.sessionId());render(player,menu);player.openInventory(menu.inventory);}
    public void tick(Player player){
        if(!(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu))return;
        try{if(!player.getUniqueId().equals(menu.owner)||!require(player).sessionId().equals(menu.session))throw new IllegalStateException("Stale vote menu");
            if(!menu.locale.equals(I18n.locale(player))){var next=new Menu(player,menu.session);next.page=menu.page;render(player,next);player.openInventory(next.inventory);}
            else render(player,menu);}
        catch(RuntimeException error){player.closeInventory();}
    }
    private void render(Player player,Menu menu){
        if(!player.getUniqueId().equals(menu.owner))throw new IllegalStateException("Vote menu belongs to another player");
        var session=require(player);if(!session.sessionId().equals(menu.session))throw new IllegalStateException("Stale vote menu");
        var options=rooms.get().regionOptions(player.getUniqueId());
        int pages=Math.max(1,(options.size()+35)/36);menu.page=Math.min(menu.page,pages-1);menu.actions.clear();
        var items=new ItemStack[54];int start=menu.page*36;
        for(int i=start;i<Math.min(start+36,options.size());i++){
            var option=options.get(i);var region=option.region();int slot=i-start;
            items[slot]=item(option.selected()?Material.LIME_CONCRETE:Material.MAP,
                    (option.selected()?I18n.text(player,"✓ 已投票 · ","✓ Your vote · "):"")+LobbyText.defaultLabel(player,region.name()),List.of(
                    I18n.text(player,"地图：%s","Map: %s",option.mapName()),
                    "X: "+coordinate(region.minX())+" ~ "+coordinate(region.maxX())+" | Z: "+coordinate(region.minZ())+" ~ "+coordinate(region.maxZ()),
                    I18n.text(player,"当前票数：%s","Votes: %s",option.votes()),
                    I18n.text(player,"点击投票；可随时改选","Click to vote; you may change your vote")),option.selected()?NamedTextColor.GREEN:NamedTextColor.AQUA);
            menu.actions.put(slot,()->{rooms.get().voteRegion(player.getUniqueId(),option.mapId(),region.id());
                player.sendMessage(UiText.success(I18n.text(player,"已投票：%s","Voted for: %s",LobbyText.defaultLabel(player,region.name()))));});
        }
        int remaining=rooms.get().remaining(session);
        items[40]=item(Material.BOOK,I18n.text(player,"开局区域投票","Starting region vote"),List.of(
                I18n.text(player,"房间：%s","Room: %s",LobbyText.defaultLabel(player,session.room().displayName())),
                remaining<0?I18n.text(player,"等待玩家 · 开始准备时截止","Waiting for players · Closes at preparation"):I18n.text(player,"剩余 %s 秒","%s seconds remaining",remaining),
                I18n.text(player,"票数最高获选；平票或无人投票则随机","Most votes wins; ties or no votes are random"),
                I18n.text(player,"每张地图各一票；只结算抽中的地图","One vote per map; only the selected map is counted"),
                I18n.text(player,"只影响开局位置；后续缩圈仍随机","Affects the initial center; later zones remain random")),NamedTextColor.GOLD);
        if(options.isEmpty())items[13]=item(Material.GRAY_DYE,I18n.text(player,"本房间未配置投票区域","No voting regions configured"),List.of(I18n.text(player,"开局区域将自动随机","The initial center will be chosen automatically")),NamedTextColor.GRAY);
        items[49]=item(Material.BARRIER,I18n.text(player,"撤回我的投票","Withdraw my votes"),List.of(),NamedTextColor.RED);
        menu.actions.put(49,()->rooms.get().clearRegionVotes(player.getUniqueId()));
        if(menu.page>0){items[45]=item(Material.ARROW,I18n.text(player,"上一页","Previous page"),List.of(),NamedTextColor.AQUA);menu.actions.put(45,()->menu.page--);}
        if(menu.page+1<pages){items[53]=item(Material.ARROW,I18n.text(player,"下一页","Next page"),List.of(),NamedTextColor.AQUA);menu.actions.put(53,()->menu.page++);}
        for(int slot=0;slot<items.length;slot++)if(!Objects.equals(menu.inventory.getItem(slot),items[slot]))menu.inventory.setItem(slot,items[slot]);
    }
    private static String coordinate(double value){return value==Math.rint(value)?Long.toString((long)value):Double.toString(value);}
    private static ItemStack item(Material material,String name,List<String> lore,NamedTextColor color){
        var stack=new ItemStack(material);var meta=stack.getItemMeta();meta.displayName(Component.text(name,color).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.lore(lore.stream().map(UiText::text).toList());stack.setItemMeta(meta);return stack;
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Menu menu))return;
        event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!player.getUniqueId().equals(menu.owner))return;
        if(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)return;
        var action=menu.actions.get(event.getRawSlot());if(action==null)return;
        plugin.getServer().getScheduler().runTask(plugin,()->{
            try{if(player.getOpenInventory().getTopInventory()!=menu.inventory||!require(player).sessionId().equals(menu.session))return;action.run();render(player,menu);}
            catch(RuntimeException error){player.sendMessage(UiText.error(I18n.error(player,error.getMessage())));player.closeInventory();}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Menu)event.setCancelled(true);}
    private final class Menu implements InventoryHolder {
        private final UUID owner,session;private final Inventory inventory;private final Locale locale;private int page;
        private final Map<Integer,Runnable> actions=new HashMap<>();
        Menu(Player player,UUID session){this.owner=player.getUniqueId();this.session=session;this.locale=I18n.locale(player);
            inventory=plugin.getServer().createInventory(this,54,UiText.heading(I18n.text(player,"大逃杀 · 开局区域投票","BattleRoyale · Starting region vote")));}
        public Inventory getInventory(){return inventory;}
    }
}
