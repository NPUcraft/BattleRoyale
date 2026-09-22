package com.lastsector.paper;
import com.lastsector.service.PluginRuntime;
import com.lastsector.progression.*;
import com.lastsector.cosmetic.*;
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
public final class PaperLobby implements Listener {
    private final JavaPlugin plugin;private final PluginRuntime runtime;private final NamespacedKey actionKey;
    private final Set<UUID> lobby=new HashSet<>();
    public PaperLobby(JavaPlugin plugin,PluginRuntime runtime){this.plugin=plugin;this.runtime=runtime;actionKey=new NamespacedKey(plugin,"lobby_action");}
    private PaperProgression data(){return runtime.progression();}
    public boolean eligible(Player player) {
        UUID id=player.getUniqueId();if(runtime.editing(id))return false;if(!runtime.recoveryReady() || player.isDead() || runtime.pendingRestore(id) || runtime.matches().frozen(id) || runtime.spectators().registry().find(id).isPresent())return false;
        var session=runtime.rooms().participant(id).orElse(null);
        return session==null || session.players().get(id).state()==com.lastsector.player.PlayerState.ELIMINATED || (session.state()==com.lastsector.session.GameState.ENDING || session.state()==com.lastsector.session.GameState.CLEANUP) && player.getWorld().equals(runtime.lobbySpawn().getWorld());
    }
    public void tick() {
        lobby.removeIf(id->plugin.getServer().getPlayer(id)==null);
        int particlesRemaining=300;
        for(var player:plugin.getServer().getOnlinePlayers()) {
            if(!eligible(player)){lobby.remove(player.getUniqueId());continue;}
            if(lobby.add(player.getUniqueId()))canonical(player);
            var profile=data().profile(player.getUniqueId());if(profile==null)continue;
            String id=profile.equipped().get(CosmeticCategory.LOBBY_EFFECT.name());var definition=id==null?null:data().config().cosmetics().get(id);
            if(particlesRemaining>=3 && definition!=null && profile.unlocks().contains(id) && player.getWorld().equals(runtime.lobbySpawn().getWorld())) {
                particlesRemaining-=3;player.spawnParticle(Particle.valueOf(definition.effectConfig().getOrDefault("particle","END_ROD")),player.getLocation().add(0,.5,0),3,.2,.2,.2,0);
            }
        }
    }
    public void canonical(Player player) {
        player.closeInventory();player.teleport(runtime.lobbySpawn());player.getInventory().clear();player.setItemOnCursor(null);
        data().config().menu().forEach((action,item)->{
            var stack=item(item.material(),item.name(),item.lore());var meta=stack.getItemMeta();meta.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);stack.setItemMeta(meta);player.getInventory().setItem(item.slot(),stack);
        });
    }
    public void command(Player player,String action,String argument) {
        if(!player.hasPermission("lastsector.play"))throw new IllegalStateException("You do not have permission to use LastSector menus");
        if(!eligible(player))throw new IllegalStateException("Leave your room or spectator session before using Lobby menus");
        if(action.equals("lobby")){canonical(player);lobby.add(player.getUniqueId());return;}
        data().check(player.getUniqueId());
        switch(action) {
            case "rooms" -> rooms(player,0);
            case "autojoin" -> runtime.rooms().autojoin(player.getUniqueId());
            case "profile" -> profile(player);
            case "leaderboard" -> board(player,new LeaderboardRepository.Query(LeaderboardRepository.Scope.LIFETIME,"",argument==null?LeaderboardRepository.Metric.RATING:LeaderboardRepository.Metric.valueOf(argument.toUpperCase(Locale.ROOT)),0,36));
            case "shop" -> shop(player,false,null,0);
            case "cosmetics" -> shop(player,true,null,0);
            default -> throw new IllegalArgumentException("Unknown Lobby action");
        }
    }
    private final class Menu implements InventoryHolder {
        private final Inventory inventory;private final Map<Integer,Runnable> actions=new HashMap<>();
        Menu(String title){inventory=plugin.getServer().createInventory(this,54,Component.text(title));}
        public Inventory getInventory(){return inventory;}
        void put(int slot,Material material,String name,List<String> lore,Runnable action){inventory.setItem(slot,item(material,name,lore));if(action!=null)actions.put(slot,action);}
    }
    private static ItemStack item(Material material,String name,List<String> lore) {
        var item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(Component.text(name));meta.lore(lore.stream().map(Component::text).toList());item.setItemMeta(meta);return item;
    }
    private Menu menu(String key){return new Menu(data().config().titles().get(key));}
    private void rooms(Player player,int page) {
        var menu=menu("rooms");int index=0;
        var all=runtime.rooms().rooms().stream().toList();
        for(var room:all.subList(Math.min(page*45,all.size()),Math.min(page*45+45,all.size()))) {
            var session=runtime.rooms().session(room.id()).orElse(null);String state=session==null?"WAITING":session.state().name();int count=session==null?0:session.players().size();
            boolean joinable=Set.of("WAITING","COUNTDOWN").contains(state)&&count<room.maxPlayers();
            menu.put(index++,joinable?Material.LIME_CONCRETE:Material.RED_CONCRETE,room.displayName(),List.of(state,count+" / "+room.maxPlayers(),"Team size: "+room.teamSize(),"Map: "+(session==null?"Pending":session.selectedMap().map(m->m.id()).orElse("Pending")),joinable?"Click to join":"Unavailable"),()->{runtime.rooms().join(player.getUniqueId(),room.id());player.closeInventory();});
        }
        if(page>0)menu.put(51,Material.ARROW,"Previous page",List.of(),()->rooms(player,page-1));
        if((page+1)*45<all.size())menu.put(53,Material.ARROW,"Next page",List.of(),()->rooms(player,page+1));
        player.openInventory(menu.inventory);
    }
    private void profile(Player player) {
        var p=data().profile(player.getUniqueId());var menu=menu("profile");
        menu.put(13,Material.PLAYER_HEAD,p.name(),List.of("Rating: "+p.rating(),"Highest Rating: "+p.highestRating(),"Kill Score: "+p.killScore(),"Matches: "+p.matches(),"Wins: "+p.wins(),"Kills: "+p.kills(),"Deaths: "+p.deaths(),"Assists: "+p.assists(),String.format(Locale.ROOT,"K/D: %.2f | Win Rate: %.2f%%",p.killDeathRatio(),p.winRate()),"Damage: "+p.damage(),"Equipped: "+p.equipped()),null);
        menu.put(31,Material.NETHER_STAR,"Your Cosmetics",List.of(),()->shop(player,true,null,0));player.openInventory(menu.inventory);
    }
    private void shop(Player player,boolean owned,CosmeticCategory category,int page) {
        var menu=menu(owned?"cosmetics":"shop");var profile=data().profile(player.getUniqueId());
        var definitions=data().config().cosmetics().values().stream().filter(d->category==null||d.category()==category).filter(d->!owned||profile.unlocks().contains(d.id())||d.price().signum()==0).sorted(Comparator.comparing(CosmeticDefinition::id)).toList();
        for(int i=page*36;i<Math.min(definitions.size(),page*36+36);i++) {
            var d=definitions.get(i);boolean has=profile.unlocks().contains(d.id());boolean equipped=d.id().equals(profile.equipped().get(d.category().name()));
            var lore=new ArrayList<>(d.description());lore.add("Price: "+d.price().toPlainString());lore.add(equipped?"EQUIPPED — click to unequip":has?"OWNED — click to equip":"LOCKED — click to unlock");
            menu.put(i-page*36,Material.valueOf(d.icon()),d.displayName(),lore,()->{
                requireLobby(player);
                if(has||owned&&d.price().signum()==0)finish(player,data().equip(player.getUniqueId(),d,equipped),()->shop(player,owned,category,page));
                else if(d.price().signum()==0)finish(player,data().buy(player.getUniqueId(),d),()->shop(player,owned,category,page));
                else confirm(player,d);
            });
        }
        for(var c:CosmeticCategory.values())menu.put(45+c.ordinal(),Material.BOOK,c.name(),List.of(),()->shop(player,owned,c,0));
        if(page>0)menu.put(51,Material.ARROW,"Previous page",List.of(),()->shop(player,owned,category,page-1));
        if((page+1)*36<definitions.size())menu.put(53,Material.ARROW,"Next page",List.of(),()->shop(player,owned,category,page+1));
        player.openInventory(menu.inventory);
    }
    private void confirm(Player player,CosmeticDefinition d) {
        var menu=menu("confirmation");
        menu.put(20,Material.LIME_CONCRETE,"Confirm: "+d.displayName(),List.of("Price: "+d.price().toPlainString()),()->{requireLobby(player);player.closeInventory();finish(player,data().buy(player.getUniqueId(),d),()->shop(player,false,d.category(),0));});
        menu.put(24,Material.RED_CONCRETE,"Cancel",List.of(),()->shop(player,false,d.category(),0));player.openInventory(menu.inventory);
    }
    private void requireLobby(Player player){if(!eligible(player))throw new IllegalStateException("Shop and equip are only available in Lobby");data().check(player.getUniqueId());}
    private void finish(Player player,java.util.concurrent.CompletableFuture<?> work,Runnable done) {
        work.whenComplete((value,error)->{if(!player.isOnline())return;if(error!=null){player.sendMessage(Component.text("Operation failed: "+error.getMessage()));return;}if(value!=null)player.sendMessage(Component.text(value.toString()));plugin.getServer().getScheduler().runTaskLater(plugin,()->{if(player.isOnline()&&eligible(player))done.run();},2);});
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
        var menu=menu("leaderboard");menu.put(13,Material.CLOCK,"Loading...",List.of(query.scope()+" "+query.period()),null);player.openInventory(menu.inventory);
        data().leaderboard(query).whenComplete((rows,error)->{
            if(!player.isOnline()||player.getOpenInventory().getTopInventory()!=menu.inventory)return;
            menu.inventory.clear();if(error!=null){menu.put(13,Material.BARRIER,"Leaderboard unavailable",List.of(),null);return;}
            if(rows.isEmpty())menu.put(13,Material.PAPER,"No data",List.of(query.period()),null);
            for(int i=0;i<rows.size();i++){var row=rows.get(i);menu.put(i,Material.PLAYER_HEAD,(query.page()*query.size()+i+1)+". "+row.name(),List.of(query.metric()+": "+row.value(),query.scope()+" "+query.period()),null);}
            for(var scope:LeaderboardRepository.Scope.values())menu.put(36+scope.ordinal(),Material.BOOK,scope.name(),List.of(),()->board(player,new LeaderboardRepository.Query(scope,current(scope,data().config().ranking().zone()),query.metric(),0,36)));
            for(var metric:LeaderboardRepository.Metric.values())menu.put(40+metric.ordinal(),Material.GOLD_INGOT,metric.name()+(query.scope()!=LeaderboardRepository.Scope.LIFETIME&&(metric==LeaderboardRepository.Metric.RATING||metric==LeaderboardRepository.Metric.KILL_SCORE)?" GAINED":""),List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),query.period(),metric,0,36)));
            if(query.scope()!=LeaderboardRepository.Scope.LIFETIME) {
                menu.put(47,Material.CLOCK,"Previous Period",List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),shift(query.scope(),query.period(),-1),query.metric(),0,36)));
                menu.put(48,Material.CLOCK,"Current Period",List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),current(query.scope(),data().config().ranking().zone()),query.metric(),0,36)));
                menu.put(49,Material.CLOCK,"Next Period",List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),shift(query.scope(),query.period(),1),query.metric(),0,36)));
            }
            if(query.page()>0)menu.put(51,Material.ARROW,"Previous page",List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),query.period(),query.metric(),query.page()-1,36)));
            if(rows.size()==query.size())menu.put(53,Material.ARROW,"Next page",List.of(),()->board(player,new LeaderboardRepository.Query(query.scope(),query.period(),query.metric(),query.page()+1,36)));
        });
    }
    private boolean control(ItemStack stack){return stack!=null&&stack.hasItemMeta()&&stack.getItemMeta().getPersistentDataContainer().has(actionKey,PersistentDataType.STRING);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof PaperLobby.Menu menu) {
            event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;
            var action=menu.actions.get(event.getRawSlot());if(action!=null&&(event.isLeftClick()||event.isRightClick()))plugin.getServer().getScheduler().runTask(plugin,()->{try{requireLobby(player);action.run();}catch(RuntimeException e){player.sendMessage(Component.text(e.getMessage()));}});return;
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
        try{command(e.getPlayer(),e.getItem().getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING),null);}catch(RuntimeException error){e.getPlayer().sendMessage(Component.text(error.getMessage()));}
    }
    public void reset(){lobby.clear();}
}
