package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.service.UiText;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import com.npucraft.battleroyale.config.ProgressionConfig;
import com.npucraft.battleroyale.config.LobbySettings;
import com.npucraft.battleroyale.progression.*;
import com.npucraft.battleroyale.cosmetic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.time.*;
/** Canonical Lobby inventory and holder-identified read-only menus. One central Lobby effects loop. */
public final class PaperLobby implements Listener, AutoCloseable {
    private final JavaPlugin plugin;private final PluginRuntime runtime;private final NamespacedKey actionKey,queueExitKey;
    private final Set<UUID> lobby=new HashSet<>(), deferredJoins=new HashSet<>(), skipLoginReturn=new HashSet<>();
    private final Map<UUID,Locale> locales=new HashMap<>();
    private LobbySettings settings=LobbySettings.DEFAULT;
    private PaperLobbyStructure structure;
    private PaperLobbyDataBoard dataBoard;
    private PaperLobbySidebar sidebar;
    private com.npucraft.battleroyale.config.LobbySidebarSettings sidebarSettings=com.npucraft.battleroyale.config.LobbySidebarSettings.DEFAULT;
    private String sidebarWorld;
    private final long sidebarStarted=System.nanoTime();
    public void configure(ProgressionConfig config,String lobbyWorld) {
        if(dataBoard!=null)dataBoard.reconfigure(config.lobbySettings());
        if(structure!=null)structure.close();
        settings=config.lobbySettings();sidebarSettings=config.sidebarSettings();sidebarWorld=lobbyWorld;
        if(sidebar==null)sidebar=new PaperLobbySidebar(plugin.getServer().getScoreboardManager(),sidebarSettings,
                error->plugin.getLogger().log(java.util.logging.Level.WARNING,"大厅侧边栏更新失败，已保留其他计分板",error));
        else sidebar.configure(sidebarSettings);
        structure=new PaperLobbyStructure(plugin,runtime,settings,lobbyWorld,this::chooseRoom);
        dataBoard=new PaperLobbyDataBoard(plugin,settings,()->data().lobbyRanking(),player->{
            try{command(player,"profile",null);}catch(RuntimeException error){player.sendMessage(UiText.error(I18n.error(player,error.getMessage())));}
        });
    }
    public boolean ready(){return structure==null||structure.ready();}
    public boolean busy(){return structure!=null&&structure.busy();}
    public void deferJoin(Player player){deferredJoins.add(player.getUniqueId());}
    public boolean deferredJoin(UUID player){return deferredJoins.contains(player);}
    private void chooseRoom(Player player,String room) {
        try {
            if(room.equals("rooms")){command(player,"rooms",null);return;}
            if(!player.hasPermission("battleroyale.play"))throw new IllegalStateException(I18n.text(player,"你没有进入比赛的权限。","You do not have permission to join a match."));
            requireLobby(player);runtime.rooms().join(player.getUniqueId(),room);showQueueExit(player);player.closeInventory();
        }catch(RuntimeException error){player.sendMessage(UiText.error(I18n.error(player,error.getMessage())));}
    }
    @EventHandler(priority=EventPriority.MONITOR)public void joined(PlayerJoinEvent event){
        lobby.remove(event.getPlayer().getUniqueId());
        if(!settings.returnOnJoin())skipLoginReturn.add(event.getPlayer().getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR)public void quit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();lobby.remove(id);locales.remove(id);deferredJoins.remove(id);skipLoginReturn.remove(id);
        if(sidebar!=null)sidebar.hide(event.getPlayer());
        // Waiting-room disconnects have already left their queue; recover only our temporary slot.
        if(runtime.rooms().participant(id).isEmpty())try{restoreQueueExit(event.getPlayer());}catch(RuntimeException error){plugin.getLogger().warning("玩家退出时无法恢复按钮原物品，已保留现状："+id+" "+error.getMessage());}
    }
    @EventHandler(priority=EventPriority.MONITOR)public void changedWorld(PlayerChangedWorldEvent event){if(sidebar!=null)sidebar.hide(event.getPlayer());}
    public PaperLobby(JavaPlugin plugin,PluginRuntime runtime){this.plugin=plugin;this.runtime=runtime;actionKey=new NamespacedKey(plugin,"lobby_action");queueExitKey=new NamespacedKey(plugin,"queue_exit_original");}
    private PaperProgression data(){return runtime.progression();}
    public boolean eligible(Player player) {
        UUID id=player.getUniqueId();if(runtime.editing(id))return false;if(!runtime.recoveryReady() || player.isDead() || runtime.pendingRestore(id) || runtime.matches().frozen(id) || runtime.spectators().registry().find(id).isPresent())return false;
        var session=runtime.rooms().participant(id).orElse(null);
        return session==null || session.players().get(id).state()==com.npucraft.battleroyale.player.PlayerState.ELIMINATED || (session.state()==com.npucraft.battleroyale.session.GameState.ENDING || session.state()==com.npucraft.battleroyale.session.GameState.CLEANUP) && player.getWorld().equals(runtime.lobbySpawn().getWorld());
    }
    public void tick() {
        lobby.removeIf(id->plugin.getServer().getPlayer(id)==null);
        // Visibility cleanup always runs, including bootstrap, profile loading and lobby-build failure.
        sidebarTick();
        if(structure!=null)structure.viewers();
        if(dataBoard!=null)dataBoard.tick(ready()&&runtime.recoveryReady()&&data()!=null&&data().ready());
        if(!runtime.recoveryReady())return;
        // Match recovery must never wait for an unrelated lobby build or menu database load.
        for(var player:plugin.getServer().getOnlinePlayers())if(deferredJoins.remove(player.getUniqueId()))runtime.joined(player);
        if(!ready()||!data().ready())return;
        int particlesRemaining=300;
        for(var player:plugin.getServer().getOnlinePlayers()) {
            UUID playerId=player.getUniqueId();
            Locale previousLocale=locales.put(playerId,I18n.locale(player));
            if(previousLocale!=null&&!previousLocale.equals(I18n.locale(player))){
                try{
                    refreshControls(player);
                    if(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu&&eligible(player))menu.refresh.run();
                }catch(RuntimeException error){player.sendMessage(UiText.error(I18n.error(player,error.getMessage())));}
            }
            var queued=runtime.rooms().participant(playerId).orElse(null);
            if(queued!=null&&QueueExitPayload.queue(queued.state())) {
                if(!runtime.pendingRestore(playerId)&&!runtime.matches().frozen(playerId)&&!runtime.editing(playerId)) {
                    try{showQueueExit(player);}catch(RuntimeException error){plugin.getLogger().warning("无法更新退出按钮："+player.getName()+" "+error.getMessage());}
                }
                lobby.remove(playerId);continue;
            }
            if(!eligible(player)){lobby.remove(playerId);continue;}
            if(skipLoginReturn.remove(playerId))lobby.add(playerId);
            if(!lobby.contains(playerId)) {
                try {canonical(player);lobby.add(playerId);}
                catch(RuntimeException error){plugin.getLogger().warning("无法将玩家送回大厅："+player.getName()+" "+error.getMessage());continue;}
            }
            var profile=data().profile(player.getUniqueId());if(profile==null)continue;
            String id=profile.equipped().get(CosmeticCategory.LOBBY_EFFECT.name());var definition=id==null?null:data().config().cosmetics().get(id);
            if(particlesRemaining>=3 && definition!=null && profile.unlocks().contains(id) && player.getWorld().equals(runtime.lobbySpawn().getWorld())) {
                particlesRemaining-=3;player.spawnParticle(Particle.valueOf(definition.effectConfig().getOrDefault("particle","END_ROD")),player.getLocation().add(0,.5,0),3,.2,.2,.2,0);
            }
        }
    }
    private void sidebarTick(){
        if(sidebar==null)return;
        var players=plugin.getServer().getOnlinePlayers();
        sidebar.retain(players.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet()));
        boolean available=sidebarSettings.enabled()&&ready()&&runtime.recoveryReady()&&data()!=null&&data().ready();
        if(!available){for(var player:players)sidebar.suspend(player);return;}
        LobbySidebarModel.Page chinese,english;
        try{
            var rooms=runtime.rooms().rooms().stream().map(room->{
                var session=runtime.rooms().session(room.id()).orElse(null);
                return new LobbySidebarModel.RoomView(room.id(),room.displayName(),session==null?0:session.players().size(),room.maxPlayers(),room.minPlayers(),
                        session==null?com.npucraft.battleroyale.session.GameState.WAITING:session.state(),session==null?-1:runtime.rooms().remaining(session));
            }).toList();
            long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-sidebarStarted);
            chinese=LobbySidebarModel.page(rooms,players.size(),Math.max(0,elapsed),sidebarSettings.pageSeconds(),Locale.CHINESE);
            english=LobbySidebarModel.page(rooms,players.size(),Math.max(0,elapsed),sidebarSettings.pageSeconds(),Locale.ENGLISH);
        }catch(RuntimeException error){
            plugin.getLogger().log(java.util.logging.Level.WARNING,"无法读取大厅房间计分板",error);
            for(var player:players)sidebar.suspend(player);return;
        }
        for(var player:players){
            try{
                UUID id=player.getUniqueId();var session=runtime.rooms().participant(id).orElse(null);
                boolean visible=LobbySidebarModel.visible(new LobbySidebarModel.Audience(!deferredJoins.contains(id),player.getWorld().getName().equals(sidebarWorld),
                        player.isDead(),runtime.pendingRestore(id),runtime.editing(id),runtime.matches().frozen(id),runtime.spectators().registry().find(id).isPresent(),session==null?null:session.state(),eligible(player)));
                sidebar.update(player,visible,I18n.chinese(player)?chinese:english);
            }catch(RuntimeException error){sidebar.suspend(player);plugin.getLogger().log(java.util.logging.Level.WARNING,"玩家大厅计分板更新失败："+player.getUniqueId(),error);}
        }
    }
    public void canonical(Player player) {
        if(!ready())throw new IllegalStateException(I18n.text(player,"大厅正在准备或需要管理员修复，请稍后再试。","The lobby is preparing or needs administrator attention. Please try again later."));
        if(!eligible(player))throw new IllegalStateException(I18n.text(player,"你的比赛或背包恢复仍在处理中，请稍后再试。","Your match or inventory restoration is still in progress. Please wait."));
        var destination=settings.buildEnabled()&&structure!=null?structure.spawn():runtime.lobbySpawn();
        if(destination==null||!player.teleport(destination))throw new IllegalStateException(I18n.text(player,"大厅传送失败，请稍后重试。","Could not teleport to the lobby. Please try again."));
        restoreQueueExit(player);
        player.closeInventory();player.getInventory().clear();player.setItemOnCursor(null);
        player.setFallDistance(0);player.setFireTicks(0);
        if(settings.protection()){player.setFoodLevel(20);player.setSaturation(20);}
        data().config().menu().forEach((action,item)->{
            var stack=configuredItem(player,item.material(),item.name(),item.lore());var meta=stack.getItemMeta();meta.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);stack.setItemMeta(meta);player.getInventory().setItem(item.slot(),stack);
        });
        locales.put(player.getUniqueId(),I18n.locale(player));
    }
    public void command(Player player,String action,String argument) {
        if(!player.hasPermission("battleroyale.play"))throw new IllegalStateException(I18n.text(player,"你没有使用大厅菜单的权限。","You do not have permission to use lobby menus."));
        if(action.equals("leave")){leaveQueue(player);return;}
        if(!ready())throw new IllegalStateException(I18n.text(player,"大厅正在准备或需要管理员修复，请稍后再试。","The lobby is preparing or needs administrator attention. Please try again later."));
        if(!eligible(player))throw new IllegalStateException(I18n.text(player,"请先离开房间或结束观战，再使用大厅菜单。","Leave your room or stop spectating before using lobby menus."));
        if(action.equals("lobby")){canonical(player);lobby.add(player.getUniqueId());return;}
        data().check(player.getUniqueId());
        switch(action) {
            case "rooms" -> rooms(player,0);
            case "autojoin" -> {runtime.rooms().autojoin(player.getUniqueId());showQueueExit(player);}
            case "profile" -> profile(player);
            case "leaderboard" -> board(player,new LeaderboardRepository.Query(LeaderboardRepository.Scope.LIFETIME,"",argument==null?LeaderboardRepository.Metric.RATING:LeaderboardRepository.Metric.valueOf(argument.toUpperCase(Locale.ROOT)),0,36));
            case "shop" -> shop(player,false,null,0);
            case "cosmetics" -> shop(player,true,null,0);
            default -> throw new IllegalArgumentException(I18n.text(player,"未知的大厅操作。","Unknown lobby action."));
        }
    }
    private boolean exitControl(ItemStack stack){return stack!=null&&stack.getType()==Material.RED_BED&&stack.hasItemMeta()
            &&"leave".equals(stack.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING))
            &&stack.getItemMeta().getPersistentDataContainer().has(queueExitKey,PersistentDataType.BYTE_ARRAY);}
    private QueueExitPayload exitPayload(ItemStack stack,UUID owner){
        var value=QueueExitPayload.decode(stack.getItemMeta().getPersistentDataContainer().get(queueExitKey,PersistentDataType.BYTE_ARRAY));
        if(!value.owner().equals(owner))throw new IllegalStateException("退出按钮不属于当前玩家，已阻止物品覆盖。");
        return value;
    }
    public void showQueueExit(Player player){
        if(player.isDead())return;
        var session=runtime.rooms().participant(player.getUniqueId()).orElse(null);
        if(session==null||!QueueExitPayload.queue(session.state())||runtime.pendingRestore(player.getUniqueId())||runtime.matches().frozen(player.getUniqueId())||runtime.editing(player.getUniqueId()))return;
        var previous=player.getInventory().getItem(8);
        if(exitControl(previous)) {
            var existing=exitPayload(previous,player.getUniqueId());
            if(existing.belongsTo(player.getUniqueId(),session.sessionId())){queueExitText(previous,I18n.locale(player));return;}
            restoreQueueExit(player);previous=player.getInventory().getItem(8);
        }
        player.getInventory().setItem(8,createQueueExitItem(plugin,player.getUniqueId(),session.sessionId(),previous,I18n.locale(player)));
    }
    /** Shared native-item factory, also exercised by the isolated Paper probe. */
    public static ItemStack createQueueExitItem(org.bukkit.plugin.Plugin plugin,UUID owner,UUID session,ItemStack previous){
        return createQueueExitItem(plugin,owner,session,previous,Locale.CHINESE);
    }
    public static ItemStack createQueueExitItem(org.bukkit.plugin.Plugin plugin,UUID owner,UUID session,ItemStack previous,Locale locale){
        byte[] original=previous==null||previous.getType().isAir()?new byte[0]:previous.serializeAsBytes();
        var payload=new QueueExitPayload(owner,session,original);
        var bed=new ItemStack(Material.RED_BED);var meta=bed.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"lobby_action"),PersistentDataType.STRING,"leave");
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"queue_exit_original"),PersistentDataType.BYTE_ARRAY,payload.encode());bed.setItemMeta(meta);
        queueExitText(bed,locale);return bed;
    }
    private static void queueExitText(ItemStack bed,Locale locale){
        var meta=bed.getItemMeta();var name=Component.text(I18n.text(locale,"退出房间","Leave room"),NamedTextColor.RED).decoration(TextDecoration.ITALIC,false);
        if(name.equals(meta.displayName()))return;
        meta.displayName(name);meta.lore(List.of(Component.text(I18n.text(locale,"右键退出排队并返回大厅","Right-click to leave the queue and return to the lobby"),NamedTextColor.GOLD).decoration(TextDecoration.ITALIC,false),
                Component.text(I18n.text(locale,"仅等待和倒计时期间可用","Available only while waiting or counting down"),NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)));bed.setItemMeta(meta);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void death(org.bukkit.event.entity.PlayerDeathEvent event){
        // Even /kill or another plugin must not turn the temporary button into a dropped, unusable wrapper.
        UUID owner=event.getEntity().getUniqueId();
        if(sidebar!=null)sidebar.hide(event.getEntity());
        try {
            var replacements=new IdentityHashMap<ItemStack,ItemStack>();
            for(var drop:event.getDrops())if(exitControl(drop))replacements.put(drop,originalQueueItem(drop,owner));
            boolean restored=false;
            for(var iterator=event.getDrops().listIterator();iterator.hasNext();){
                var drop=iterator.next();if(!replacements.containsKey(drop))continue;
                var original=replacements.get(drop);
                if(event.getKeepInventory()||restored||original==null)iterator.remove();else{iterator.set(original);restored=true;}
            }
        }catch(RuntimeException error){plugin.getLogger().warning("死亡时无法解码退出按钮，已保留原掉落供核查："+owner+" "+error.getMessage());}
    }
    /** Called before durable capture, never after match loadouts have been applied. */
    public void beforeMatchSnapshot(Collection<UUID> players){
        // Decode every original first, so corrupt controls abort without partially changing a roster.
        var originals=new LinkedHashMap<Player,ItemStack>();
        for(UUID id:players){var player=plugin.getServer().getPlayer(id);if(player!=null){var current=player.getInventory().getItem(8);if(exitControl(current))originals.put(player,originalQueueItem(current,id));}}
        originals.forEach((player,item)->player.getInventory().setItem(8,item));
        if(sidebar!=null)for(UUID id:players){var player=plugin.getServer().getPlayer(id);if(player!=null)sidebar.hide(player);}
    }
    private ItemStack originalQueueItem(ItemStack bed,UUID owner){
        byte[] original=exitPayload(bed,owner).original();return original.length==0?null:ItemStack.deserializeBytes(original);
    }
    private void restoreQueueExit(Player player){
        var current=player.getInventory().getItem(8);if(exitControl(current))player.getInventory().setItem(8,originalQueueItem(current,player.getUniqueId()));
    }
    private void leaveQueue(Player player){
        UUID id=player.getUniqueId();var session=runtime.rooms().participant(id).orElse(null);
        if(session==null){player.sendMessage(UiText.warning(player,"你当前没有正在排队的房间。","You are not currently queued for a room."));return;}
        if(!runtime.recoveryReady()||runtime.pendingRestore(id)||runtime.matches().frozen(id)||!QueueExitPayload.queue(session.state()))
            throw new IllegalStateException(I18n.text(player,"比赛已经开始或正在准备，不能使用退出床离开。","The match has started or is preparing. The leave bed is unavailable."));
        // Validate and restore the displaced slot before membership changes; a failed leave remains harmless.
        restoreQueueExit(player);runtime.rooms().leave(id);
        if(ready()&&eligible(player)){canonical(player);lobby.add(id);}
    }
    private final class Menu implements InventoryHolder {
        private final Inventory inventory;private final Map<Integer,Runnable> actions=new HashMap<>();
        private final Runnable refresh;
        Menu(String title,Runnable refresh){this.refresh=refresh;inventory=plugin.getServer().createInventory(this,54,UiText.heading(title));}
        public Inventory getInventory(){return inventory;}
        void put(int slot,Material material,String name,List<String> lore,Runnable action){inventory.setItem(slot,item(material,name,lore));if(action!=null)actions.put(slot,action);}
    }
    private static ItemStack item(Material material,String name,List<String> lore) {
        var item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(UiText.heading(name));meta.lore(lore.stream().map(UiText::text).toList());item.setItemMeta(meta);return item;
    }
    private static ItemStack configuredItem(Player player,Material material,String name,List<String> lore){return item(material,LobbyText.defaultLabel(player,name),lore.stream().map(value->LobbyText.defaultLabel(player,value)).toList());}
    private void refreshControls(Player player){
        var session=runtime.rooms().participant(player.getUniqueId()).orElse(null);
        if(!eligible(player)&&(session==null||!QueueExitPayload.queue(session.state())))return;
        data().config().menu().forEach((action,definition)->{
            var current=player.getInventory().getItem(definition.slot());
            if(!control(current)||!action.equals(current.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING)))return;
            var next=configuredItem(player,definition.material(),definition.name(),definition.lore());var meta=current.getItemMeta();
            meta.displayName(next.getItemMeta().displayName());meta.lore(next.getItemMeta().lore());current.setItemMeta(meta);
        });
    }
    private Menu menu(Player player,String key,Runnable refresh){return new Menu(LobbyText.defaultLabel(player,data().config().titles().get(key)),refresh);}
    private void rooms(Player player,int page) {
        var menu=menu(player,"rooms",()->rooms(player,page));int index=0;
        var all=runtime.rooms().rooms().stream().toList();
        for(var room:all.subList(Math.min(page*45,all.size()),Math.min(page*45+45,all.size()))) {
            var session=runtime.rooms().session(room.id()).orElse(null);String state=session==null?"WAITING":session.state().name();int count=session==null?0:session.players().size();
            boolean joinable=Set.of("WAITING","COUNTDOWN").contains(state)&&count<room.maxPlayers();
            String pending=I18n.text(player,"等待抽取","Pending selection");
            menu.put(index++,joinable?Material.LIME_CONCRETE:Material.RED_CONCRETE,LobbyText.defaultLabel(player,room.displayName()),List.of(
                    I18n.text(player,"状态：%s","Status: %s",I18n.state(player,state)),I18n.text(player,"人数：%s / %s","Players: %s / %s",count,room.maxPlayers()),
                    I18n.text(player,"每队人数：%s","Team size: %s",room.teamSize()),I18n.text(player,"地图：%s","Map: %s",session==null?pending:session.selectedMap().map(m->m.displayName()).orElse(pending)),
                    I18n.text(player,joinable?"点击加入比赛":"暂时无法加入",joinable?"Click to join":"Unavailable right now")),()->{runtime.rooms().join(player.getUniqueId(),room.id());showQueueExit(player);player.closeInventory();});
        }
        if(page>0)menu.put(51,Material.ARROW,I18n.text(player,"上一页","Previous page"),List.of(),()->rooms(player,page-1));
        if((page+1)*45<all.size())menu.put(53,Material.ARROW,I18n.text(player,"下一页","Next page"),List.of(),()->rooms(player,page+1));
        player.openInventory(menu.inventory);
    }
    private void profile(Player player) {
        var p=data().profile(player.getUniqueId());var menu=menu(player,"profile",()->profile(player));
        menu.put(13,Material.PLAYER_HEAD,p.name(),List.of(I18n.text(player,"竞技评分：%s","Rating: %s",p.rating()),I18n.text(player,"历史最高评分：%s","Highest rating: %s",p.highestRating()),
                I18n.text(player,"击杀积分：%s","Kill score: %s",p.killScore()),I18n.text(player,"参赛场次：%s","Matches: %s",p.matches()),I18n.text(player,"胜场：%s","Wins: %s",p.wins()),
                I18n.text(player,"击杀：%s","Kills: %s",p.kills()),I18n.text(player,"阵亡：%s","Deaths: %s",p.deaths()),I18n.text(player,"助攻：%s","Assists: %s",p.assists()),
                I18n.text(player,"击杀/阵亡：%.2f | 胜率：%.2f%%","K/D: %.2f | Win rate: %.2f%%",p.killDeathRatio(),p.winRate()),
                I18n.text(player,"累计伤害：%s","Total damage: %s",p.damage()),I18n.text(player,"已装备：%s","Equipped: %s",equippedNames(player,p.equipped()))),null);
        menu.put(31,Material.NETHER_STAR,I18n.text(player,"我的外观","My cosmetics"),List.of(),()->shop(player,true,null,0));player.openInventory(menu.inventory);
    }
    private void shop(Player player,boolean owned,CosmeticCategory category,int page) {
        var menu=menu(player,owned?"cosmetics":"shop",()->shop(player,owned,category,page));var profile=data().profile(player.getUniqueId());
        var definitions=data().config().cosmetics().values().stream().filter(d->category==null||d.category()==category).filter(d->!owned||profile.unlocks().contains(d.id())||d.price().signum()==0).sorted(Comparator.comparing(CosmeticDefinition::id)).toList();
        for(int i=page*36;i<Math.min(definitions.size(),page*36+36);i++) {
            var d=definitions.get(i);boolean has=profile.unlocks().contains(d.id());boolean equipped=d.id().equals(profile.equipped().get(d.category().name()));
            var lore=new ArrayList<>(d.description().stream().map(value->LobbyText.defaultLabel(player,value)).toList());lore.add(I18n.text(player,"价格：%s","Price: %s",d.price().toPlainString()));
            lore.add(equipped?I18n.text(player,"已装备 · 点击卸下","Equipped · Click to unequip"):has?I18n.text(player,"已拥有 · 点击装备","Owned · Click to equip"):I18n.text(player,"未解锁 · 点击购买","Locked · Click to buy"));
            menu.put(i-page*36,Material.valueOf(d.icon()),LobbyText.defaultLabel(player,d.displayName()),lore,()->{
                requireLobby(player);
                if(has||owned&&d.price().signum()==0)finish(player,data().equip(player.getUniqueId(),d,equipped),()->shop(player,owned,category,page));
                else if(d.price().signum()==0)finish(player,data().buy(player.getUniqueId(),d),()->shop(player,owned,category,page));
                else confirm(player,d);
            });
        }
        for(var c:CosmeticCategory.values())menu.put(45+c.ordinal(),Material.BOOK,categoryName(player,c),List.of(),()->shop(player,owned,c,0));
        if(page>0)menu.put(51,Material.ARROW,I18n.text(player,"上一页","Previous page"),List.of(),()->shop(player,owned,category,page-1));
        if((page+1)*36<definitions.size())menu.put(53,Material.ARROW,I18n.text(player,"下一页","Next page"),List.of(),()->shop(player,owned,category,page+1));
        player.openInventory(menu.inventory);
    }
    private void confirm(Player player,CosmeticDefinition d) {
        var menu=menu(player,"confirmation",()->confirm(player,d));
        menu.put(20,Material.LIME_CONCRETE,I18n.text(player,"确认购买：%s","Confirm purchase: %s",LobbyText.defaultLabel(player,d.displayName())),List.of(I18n.text(player,"价格：%s","Price: %s",d.price().toPlainString())),()->{requireLobby(player);player.closeInventory();finish(player,data().buy(player.getUniqueId(),d),()->shop(player,false,d.category(),0));});
        menu.put(24,Material.RED_CONCRETE,I18n.text(player,"取消","Cancel"),List.of(),()->shop(player,false,d.category(),0));player.openInventory(menu.inventory);
    }
    private void requireLobby(Player player){if(!ready()||!eligible(player))throw new IllegalStateException(I18n.text(player,"只能在大厅中购买或装备外观。","Cosmetics can only be purchased or equipped in the lobby."));data().check(player.getUniqueId());}
    private void finish(Player player,java.util.concurrent.CompletableFuture<?> work,Runnable done) {
        work.whenComplete((value,error)->{if(!player.isOnline())return;if(error!=null){player.sendMessage(UiText.error(player,"操作失败：%s","Action failed: %s",I18n.error(player,error.getMessage())));return;}if(value!=null)player.sendMessage(UiText.text(I18n.error(player,value.toString())));plugin.getServer().getScheduler().runTaskLater(plugin,()->{if(player.isOnline()&&eligible(player))done.run();},2);});
    }
    private static String current(LeaderboardRepository.Scope scope,ZoneId zone) {
        var keys=PeriodKeys.at(Instant.now(),zone);return switch(scope){case LIFETIME->"";case DAY->keys.day();case WEEK->keys.week();case MONTH->keys.month();};
    }
    private static String shift(LeaderboardRepository.Scope scope,String key,int amount) {
        return switch(scope) {
            case LIFETIME -> "";
            case DAY -> LocalDate.parse(key).plusDays(amount).toString();
            case MONTH -> YearMonth.parse(key).plusMonths(amount).toString();
            case WEEK -> {int year=Integer.parseInt(key.substring(0,4)),week=Integer.parseInt(key.substring(6));var date=LocalDate.of(year,1,4).with(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR,week).plusWeeks(amount);yield PeriodKeys.at(date.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC).week();}
        };
    }
    private void board(Player player,LeaderboardRepository.Query query) {
        var menu=menu(player,"leaderboard",()->board(player,query));menu.put(13,Material.CLOCK,I18n.text(player,"正在加载…","Loading…"),List.of(scopeName(player,query.scope())+" "+query.period()),null);player.openInventory(menu.inventory);
        data().leaderboard(query).whenComplete((rows,error)->{
            if(!player.isOnline()||player.getOpenInventory().getTopInventory()!=menu.inventory)return;
            menu.inventory.clear();if(error!=null){menu.put(13,Material.BARRIER,I18n.text(player,"排行榜暂不可用","Leaderboard unavailable"),List.of(),null);return;}
            if(rows.isEmpty())menu.put(13,Material.PAPER,I18n.text(player,"暂无数据","No data yet"),List.of(query.period()),null);
            for(int i=0;i<rows.size();i++){var row=rows.get(i);menu.put(i,Material.PLAYER_HEAD,(query.page()*query.size()+i+1)+". "+row.name(),List.of(metricName(player,query.metric())+": "+row.value(),scopeName(player,query.scope())+" "+query.period()),null);}
            for(var scope:LeaderboardRepository.Scope.values())menu.put(36+scope.ordinal(),Material.BOOK,scopeName(player,scope),List.of(),()->board(player,new LeaderboardRepository.Query(scope,current(scope,data().config().ranking().zone()),query.metric(),0,36)));
            for(var metric:LeaderboardRepository.Metric.values())menu.put(40+metric.ordinal(),Material.GOLD_INGOT,metricName(player,metric)+(query.scope()!=LeaderboardRepository.Scope.LIFETIME&&(metric==LeaderboardRepository.Metric.RATING||metric==LeaderboardRepository.Metric.KILL_SCORE)?I18n.text(player,"（本期增加）"," (period gain)"):""),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),query.period(),metric,0,36)));
            if(query.scope()!=LeaderboardRepository.Scope.LIFETIME) {
                menu.put(47,Material.CLOCK,I18n.text(player,"上一期","Previous period"),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),shift(query.scope(),query.period(),-1),query.metric(),0,36)));
                menu.put(48,Material.CLOCK,I18n.text(player,"本期","Current period"),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),current(query.scope(),data().config().ranking().zone()),query.metric(),0,36)));
                menu.put(49,Material.CLOCK,I18n.text(player,"下一期","Next period"),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),shift(query.scope(),query.period(),1),query.metric(),0,36)));
            }
            if(query.page()>0)menu.put(51,Material.ARROW,I18n.text(player,"上一页","Previous page"),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),query.period(),query.metric(),query.page()-1,36)));
            if(rows.size()==query.size())menu.put(53,Material.ARROW,I18n.text(player,"下一页","Next page"),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),query.period(),query.metric(),query.page()+1,36)));
        });
    }
    private String equippedNames(Player player,Map<String,String> equipped) {
        if(equipped.isEmpty())return I18n.text(player,"无","None");
        return equipped.values().stream().map(id->{var definition=data().config().cosmetics().get(id);return definition==null?id:LobbyText.defaultLabel(player,definition.displayName());}).sorted().collect(java.util.stream.Collectors.joining(I18n.text(player,"、",", ")));
    }
    private static String categoryName(Player player,CosmeticCategory category){return switch(category){case KILL_EFFECT->I18n.text(player,"击杀特效","Kill effects");case WIN_EFFECT->I18n.text(player,"胜利特效","Victory effects");case DEATHBOX_SKIN->I18n.text(player,"战利品箱外观","Deathbox skins");case LOBBY_EFFECT->I18n.text(player,"大厅特效","Lobby effects");};}
    private static String scopeName(Player player,LeaderboardRepository.Scope scope){return switch(scope){case LIFETIME->I18n.text(player,"总榜","Lifetime");case DAY->I18n.text(player,"日榜","Daily");case WEEK->I18n.text(player,"周榜","Weekly");case MONTH->I18n.text(player,"月榜","Monthly");};}
    private static String metricName(Player player,LeaderboardRepository.Metric metric){return switch(metric){case RATING->I18n.text(player,"竞技评分","Rating");case KILL_SCORE->I18n.text(player,"击杀积分","Kill score");case WINS->I18n.text(player,"胜场","Wins");case KILLS->I18n.text(player,"击杀","Kills");case ASSISTS->I18n.text(player,"助攻","Assists");case DAMAGE->I18n.text(player,"伤害","Damage");};}
    private boolean control(ItemStack stack){return stack!=null&&stack.hasItemMeta()&&stack.getItemMeta().getPersistentDataContainer().has(actionKey,PersistentDataType.STRING);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof PaperLobby.Menu menu) {
            event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;
            var action=menu.actions.get(event.getRawSlot());if(action!=null&&(event.getClick()==ClickType.LEFT||event.getClick()==ClickType.RIGHT))plugin.getServer().getScheduler().runTask(plugin,()->{try{requireLobby(player);action.run();}catch(RuntimeException e){player.sendMessage(UiText.error(I18n.error(player,e.getMessage())));}});return;
        }
        if(control(event.getCurrentItem())||control(event.getCursor())||event.getHotbarButton()>=0&&control(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))||event.getClick()==ClickType.SWAP_OFFHAND&&control(event.getWhoClicked().getInventory().getItemInOffHand()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof PaperLobby.Menu || control(event.getOldCursor()) || event.getRawSlots().stream().anyMatch(slot->control(event.getView().getItem(slot))))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(control(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(control(e.getMainHandItem())||control(e.getOffHandItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(control(e.getItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(control(e.getItemInHand()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) {
        if(!control(e.getItem()))return;e.setCancelled(true);
        if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND||!e.getAction().isRightClick())return;
        try{
            String action=e.getItem().getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
            if("leave".equals(action)){
                if(!exitControl(e.getItem()))return;
                var value=exitPayload(e.getItem(),e.getPlayer().getUniqueId());
                var current=runtime.rooms().participant(e.getPlayer().getUniqueId()).orElse(null);
                if(current!=null&&!value.belongsTo(e.getPlayer().getUniqueId(),current.sessionId()))throw new IllegalStateException(I18n.text(e.getPlayer(),"退出按钮属于上一间房，请稍后重试。","The leave button belongs to a previous room. Please try again."));
            }
            command(e.getPlayer(),action,null);
        }catch(RuntimeException error){e.getPlayer().sendMessage(UiText.error(I18n.error(e.getPlayer(),error.getMessage())));}
    }
    public void reset(){lobby.clear();}
    @Override public void close(){
        try {
            for(var player:plugin.getServer().getOnlinePlayers())try{restoreQueueExit(player);}catch(RuntimeException error){plugin.getLogger().warning("关闭大厅时无法恢复按钮原物品，已保留现状："+player.getUniqueId()+" "+error.getMessage());}
        }finally{
            try{if(sidebar!=null)sidebar.close();}finally{
                try{if(dataBoard!=null)dataBoard.close();}finally{try{if(structure!=null)structure.close();}finally{deferredJoins.clear();lobby.clear();locales.clear();skipLoginReturn.clear();}}
            }
        }
    }
}
