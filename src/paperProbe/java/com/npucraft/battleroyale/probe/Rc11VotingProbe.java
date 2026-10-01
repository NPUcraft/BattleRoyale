package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.PaperRegionVoteMenu;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.service.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Native inventories/items/events plus an isolated real room service; players and world IO are recorders. */
public final class Rc11VotingProbe {
    private final JavaPlugin plugin;
    private final YamlConfiguration report=new YamlConfiguration();
    private final Map<UUID,Viewer> viewers=new HashMap<>();
    private final List<Throwable> failures=new ArrayList<>();
    private final List<CompletableFuture<GameWorld>> pendingWorlds=new ArrayList<>();
    private RoomRuntimeService rooms;
    private Supplier<RoomRuntimeService> serviceFactory;
    private int serviceLookups;
    private PaperRegionVoteMenu menu;
    private Clockwork clock;
    private ConfigurationSnapshot configuration;
    private Viewer first,second,outsider;
    private boolean unavailable,busy;
    public Rc11VotingProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc11"),"Explicit isolated rc11 flag");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No native players online");
        require(!busy,"Voting probe already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc11voting all");
        busy=true;CompletableFuture<Void> work;
        try{
            setup();openingAndLanguages();
            work=chooseFirst().thenCompose(unused->abuseAndOwnership())
                    .thenCompose(unused->paginationAndWithdrawal()).thenCompose(unused->staleRoomAndPermissions())
                    .thenCompose(unused->disconnectBeforeDispatch()).thenCompose(unused->replaceRoomService()).thenCompose(unused->countdownFreeze());
        }catch(Throwable failure){work=CompletableFuture.failedFuture(failure);}
        work.whenComplete((unused,error)->finish(sender,error));
    }
    private void setup(){
        var regions=new ArrayList<InitialZoneCenters.Region>();
        for(int i=0;i<40;i++)regions.add(new InitialZoneCenters.Region("r"+i,i==0?"出生点附近":i==1?"<Castle & Keep>":"Region "+i,i*10,i*10+5,0,5));
        var named=new InitialZoneCenters(regions);
        var profile=new ZoneProfile("vote",List.of(new ZoneProfile.InitialSize(5,200)),
                List.of(new ZoneProfile.Stage(Duration.ofSeconds(60),Duration.ofSeconds(30),0,1,0,1)),0,
                Map.of("city",named,"desert",new InitialZoneCenters(regions.subList(0,2))));
        var mapRoot=plugin.getDataFolder().toPath().resolve("rc11/vote-model-fixture");
        var maps=List.of(new MapTemplate("city","Native City",mapRoot.resolve("city"),new PlayableArea(-2000,2000,-2000,2000)),
                new MapTemplate("desert","Custom Desert",mapRoot.resolve("desert"),new PlayableArea(-2000,2000,-2000,2000)));
        var definitions=List.of(room("a",List.of("city","desert")),room("b",List.of("city")));
        configuration=new ConfigurationSnapshot(new PluginSettings(false,"sqlite","none",mapRoot.resolve("runtime")),definitions,maps,List.of(profile));
        clock=new Clockwork();var messages=new MessageService(plugin.getLogger());
        var players=new PlayerGateway(){
            public boolean toLobby(Collection<UUID> ids){return true;}
            public void notify(Collection<UUID> ids,String event,Object... args){for(UUID id:ids){var viewer=viewers.get(id);if(viewer!=null)messages.event(viewer.player,event,args);}}
            public void error(String context,Throwable error){failures.add(new IllegalStateException(context,error));}
        };
        var worlds=new WorldProvider(){
            public CompletionStage<GameWorld> prepare(UUID id,String room,MapTemplate map,BooleanSupplier current){var pending=new CompletableFuture<GameWorld>();pendingWorlds.add(pending);return pending;}
            public CompletionStage<Void> release(GameWorld world){throw new AssertionError("Probe never creates or releases a game world");}
            public boolean busy(){return pendingWorlds.stream().anyMatch(future->!future.isDone());}
            public void close(){pendingWorlds.forEach(future->future.cancel(false));}
        };
        var matches=new MatchLifecycle(){
            public void preparing(GameSession session){session.initialZone(profile.initialZone(session.selectedMap().orElseThrow(),session.players().size(),new Random(311),session.initialRegionId().orElse(null)));}
            public void start(GameSession session,Runnable ready,Consumer<Throwable> failed){throw new AssertionError("Probe stays at PREPARING");}
            public void running(GameSession session,Consumer<Throwable> failed){throw new AssertionError("No match starts in this menu fixture");}
            public void stop(GameSession session,Runnable drained){drained.run();}
            public void disconnected(UUID player){}
            public void close(){}
        };
        rooms=null;serviceLookups=0;
        menu=new PaperRegionVoteMenu(plugin,()->{serviceLookups++;return rooms;},player->unavailable);
        require(serviceLookups==0,"Menu construction never dereferences the not-yet-created room service");
        serviceFactory=()->new RoomRuntimeService(()->configuration,new SessionManager(),clock,(room,candidates)->candidates.stream().filter(map->room.mapPool().contains(map.id())).findFirst().orElseThrow(),
                worlds,players,Clock.systemUTC(),matches);
        rooms=serviceFactory.get();
        report.set("composition.lazy-constructor-before-rooms",true);
        first=new Viewer(Locale.ENGLISH);second=new Viewer(Locale.SIMPLIFIED_CHINESE);outsider=new Viewer(Locale.ENGLISH);
        report.set("platform",plugin.getServer().getVersion());report.set("fixture.regions-city",40);report.set("fixture.regions-desert",2);
    }
    private RoomDefinition room(String id,List<String> maps){return new RoomDefinition(id,"Room "+id,2,5,1,Duration.ZERO,maps,"starter","vote",true,Duration.ofSeconds(60));}
    private void openingAndLanguages(){
        rejected(()->menu.open(outsider.player),"Outsider cannot open voting");
        rooms.join(first.id,"a");menu.open(first.player);
        require(first.top().getSize()==54&&first.top().getHolder()!=null,"Actual Paper voting inventory has its own holder and 54 native slots");
        require(name(first,40).equals("Starting region vote"),"English info item");
        require(name(first,0).equals("Near spawn")&&name(first,1).equals("<Castle & Keep>"),"Default region is translated and custom name remains literal");
        require(lore(first,40).contains("Waiting for players"),"WAITING status is visible");
        var announcement=first.messages.stream().filter(component->plain(component).contains("Starting region voting is open")).findFirst().orElseThrow();
        require(ClickEvent.runCommand("/br vote").equals(announcement.clickEvent()),"Room event supplies a native clickable /br vote component");
        rooms.join(second.id,"a");menu.open(second.player);menu.tick(first.player);
        require(name(second,40).equals("开局区域投票")&&name(second,0).equals("出生点附近"),"Chinese menu text");
        require(lore(first,40).contains("60 seconds remaining"),"COUNTDOWN status appears");
        clock.pulse(1);menu.tick(first.player);require(lore(first,40).contains("59 seconds remaining"),"Countdown lore refreshes without client participation");
        Inventory before=first.top();first.locale=Locale.SIMPLIFIED_CHINESE;menu.tick(first.player);
        require(name(first,40).equals("开局区域投票")&&name(first,0).equals("出生点附近"),"Open menu follows a changed client locale");
        report.set("language.locale-refresh-replaced-inventory",before!=first.top());
        first.locale=Locale.ENGLISH;menu.tick(first.player);
        report.set("language","English/Chinese default labels, literal custom names, current countdown, and native clickable invitation");
    }
    private CompletableFuture<Void> chooseFirst(){
        click(first,0,ClickType.LEFT);
        return delay().thenRun(()->{
            require(selected(first,"city").equals("r0")&&votes(first)==1,"Scheduled native click records one vote");
            require(first.top().getItem(0).getType()==Material.LIME_CONCRETE&&name(first,0).contains("Your vote"),"Selected region has native item feedback");
            require(first.messages.stream().map(Rc11VotingProbe::plain).anyMatch(text->text.contains("Voted for: Near spawn")),"Opening choice notification is localized");
            click(first,0,ClickType.RIGHT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(votes(first)==1,"Repeated click remains one vote");
            report.set("native-clicks.single-vote-and-selection-feedback",true);
        });
    }
    private CompletableFuture<Void> abuseAndOwnership(){
        byte[][] before=bytes(first.top());
        for(var type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.MIDDLE,ClickType.DROP,ClickType.SWAP_OFFHAND))click(first,1,type);
        click(first,55,ClickType.LEFT);click(first,-999,ClickType.LEFT);
        var drag=new InventoryDragEvent(first.view,new ItemStack(Material.STONE),new ItemStack(Material.STONE),false,Map.of(0,new ItemStack(Material.STONE)));
        menu.drag(drag);require(drag.isCancelled(),"Native drag event cannot mutate voting inventory");
        second.show(first.top());click(second,1,ClickType.LEFT);menu.tick(second.player);
        require(second.closed,"A foreign viewer sharing the same native inventory is closed without rebinding actions");
        return delay().thenRun(()->{
            require(votes(first)==1&&selected(first,"city").equals("r0"),"Unsupported clicks and foreign owner cannot vote");
            require(Arrays.deepEquals(before,bytes(first.top())),"Unsupported operations preserve native item contents");
            click(first,1,ClickType.LEFT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(selected(first,"city").equals("r1")&&rooms.regionOptions(second.id).stream().noneMatch(RoomRuntimeService.RegionOption::selected),"Foreign tick never rebinds owner's later action to another player");
            menu.open(second.player);report.set("native-clicks.abuse-and-owner-isolation",true);
        });
    }
    private CompletableFuture<Void> paginationAndWithdrawal(){
        click(first,53,ClickType.LEFT);
        return delay().thenRun(()->{
            require(name(first,0).equals("Region 36")&&first.top().getItem(45).getType()==Material.ARROW,"Native second page contains the correct bounded region slice");
            click(first,1,ClickType.LEFT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(selected(first,"city").equals("r37")&&votes(first)==1,"Changing page and selection replaces the first vote");
            require(lore(first,4).contains("Map: Custom Desert"),"Additional map choices identify their map");
            click(first,4,ClickType.LEFT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(selected(first,"city").equals("r37")&&selected(first,"desert").equals("r0")&&votes(first)==2,"Each candidate map has its independent single vote");
            click(first,49,ClickType.LEFT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(votes(first)==0&&rooms.regionOptions(first.id).stream().noneMatch(RoomRuntimeService.RegionOption::selected),"Withdraw clears only this player's choices across candidate maps");
            click(first,45,ClickType.LEFT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(name(first,0).equals("Near spawn"),"Previous page returns to the first region");
            report.set("pagination","42 options across two maps, bounded pages, changed vote and withdrawal passed");
        });
    }
    private CompletableFuture<Void> staleRoomAndPermissions(){
        click(first,0,ClickType.LEFT);rooms.leave(first.id);rooms.join(first.id,"b");
        return delay().thenRun(()->{
            require(votes(first)==0&&votes(second)==0,"A queued click from the previous room cannot vote after membership changes");
            menu.tick(first.player);require(first.closed,"Stale session inventory closes on refresh");
            menu.open(first.player);click(first,0,ClickType.LEFT);first.permitted=false;
        }).thenCompose(unused->delay()).thenRun(()->{
            require(votes(first)==0&&first.closed,"Permission revoked before dispatch blocks the queued vote");
            rejected(()->menu.open(first.player),"Permission gate blocks opening");first.permitted=true;
            first.dead=true;rejected(()->menu.open(first.player),"Dead player cannot open");first.dead=false;
            unavailable=true;rejected(()->menu.open(first.player),"Recovery/frozen predicate blocks opening");unavailable=false;
            menu.open(first.player);unavailable=true;menu.tick(first.player);require(first.closed,"A newly blocked player loses the open menu");unavailable=false;
            report.set("guards","Stale room/session, permission revoked between click and dispatch, dead and runtime-unavailable gates passed");
        });
    }
    private CompletableFuture<Void> disconnectBeforeDispatch(){
        menu.open(first.player);click(first,0,ClickType.LEFT);rooms.disconnected(first.id);
        return delay().thenRun(()->{
            require(rooms.participant(first.id).isEmpty()&&rooms.session("b").isEmpty(),"Waiting-room disconnect removes membership and retires an empty ballot");
            rooms.join(first.id,"a");menu.open(first.player);require(votes(first)==0,"Rejoining does not inherit the departed player's vote");
            report.set("disconnect.queued-action-rejected-and-no-vote-leak",true);
        });
    }
    private CompletableFuture<Void> replaceRoomService(){
        var previous=rooms;UUID oldSession=previous.participant(first.id).orElseThrow().sessionId();
        menu.open(first.player);click(first,0,ClickType.LEFT);
        previous.leave(first.id);previous.leave(second.id);
        require(previous.canReload(),"Replacement occurs only after the real service permits reload");
        rooms=serviceFactory.get();previous.close();
        rooms.join(first.id,"a");rooms.join(second.id,"a");
        require(!rooms.participant(first.id).orElseThrow().sessionId().equals(oldSession),"Replacement owns a new session identity");
        return delay().thenRun(()->{
            require(votes(first)==0&&previous.participant(first.id).isEmpty(),"Queued old-service click cannot write to either retired or replacement service");
            menu.tick(first.player);require(first.closed,"Old inventory closes after room service replacement");
            menu.open(first.player);click(first,0,ClickType.LEFT);
        }).thenCompose(unused->delay()).thenRun(()->{
            require(selected(first,"city").equals("r0")&&votes(first)==1,"Existing menu controller reads the replacement service for new votes");
            require(previous.participant(first.id).isEmpty()&&serviceLookups>0,"Controller never resurrects retired room membership");
            report.set("composition.reload-replacement-and-stale-action",true);
        });
    }    private CompletableFuture<Void> countdownFreeze(){
        rooms.voteRegion(first.id,"city","r1");rooms.voteRegion(second.id,"city","r1");menu.tick(first.player);
        click(first,0,ClickType.LEFT);clock.pulse(60);
        var session=rooms.participant(first.id).orElseThrow();
        require(session.state()==GameState.PREPARING&&session.initialRegionId().orElseThrow().equals("r1"),"Countdown freezes the plurality before pending world preparation");
        require(session.initialRegionName().orElseThrow().equals("<Castle & Keep>"),"Frozen name remains literal");
        var region=configuration.zoneProfiles().getFirst().initialCenters().get("city").region("r1").orElseThrow();
        var initial=session.initialZone().orElseThrow();require(region.contains(initial.centerX(),initial.centerZ()),"Frozen rectangle supplies the initial center");
        rejected(()->rooms.voteRegion(first.id,"city","r0"),"Service rejects votes after preparation begins");
        menu.tick(first.player);require(first.closed,"Open voting menu closes at preparation");
        return delay().thenRun(()->{
            require(session.initialRegionId().orElseThrow().equals("r1")&&rooms.regionOptions(first.id).isEmpty(),"The last queued click cannot alter the frozen winner");
            require(pendingWorlds.size()==1&&!pendingWorlds.getFirst().isDone(),"World IO remains a recorder, with no physical match world or player preparation");
            require(failures.isEmpty(),"No room lifecycle error was hidden: "+failures);
            report.set("freeze.region",session.initialRegionId().orElseThrow());report.set("freeze.center-x",initial.centerX());report.set("freeze.center-z",initial.centerZ());
            report.set("freeze.phase-and-queued-action","COUNTDOWN -> PREPARING freezes once; stale scheduled click is rejected");
        });
    }
    private String selected(Viewer viewer,String map){return rooms.regionOptions(viewer.id).stream().filter(option->option.mapId().equals(map)&&option.selected()).findFirst().orElseThrow().region().id();}
    private int votes(Viewer viewer){return rooms.regionOptions(viewer.id).stream().mapToInt(RoomRuntimeService.RegionOption::votes).sum();}
    private void click(Viewer viewer,int slot,ClickType type){
        var event=new InventoryClickEvent(viewer.view,InventoryType.SlotType.CONTAINER,slot,type,InventoryAction.UNKNOWN,0);
        menu.click(event);require(event.isCancelled(),"Voting handler cancels native event "+type+" slot="+slot);
    }
    private String name(Viewer viewer,int slot){return plain(Objects.requireNonNull(viewer.top().getItem(slot)).getItemMeta().displayName());}
    private String lore(Viewer viewer,int slot){return Objects.requireNonNull(viewer.top().getItem(slot)).getItemMeta().lore().stream().map(Rc11VotingProbe::plain).reduce("",(a,b)->a+"\n"+b);}
    private static byte[][] bytes(Inventory inventory){return Arrays.stream(inventory.getContents()).map(item->item==null?null:item.serializeAsBytes()).toArray(byte[][]::new);}
    private CompletableFuture<Void> delay(){var future=new CompletableFuture<Void>();plugin.getServer().getScheduler().runTaskLater(plugin,()->future.complete(null),2);return future;}
    private void finish(CommandSender sender,Throwable error){
        Throwable failure=error;
        try{if(menu!=null)HandlerList.unregisterAll(menu);if(rooms!=null)rooms.close();}
        catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        report.set("status",failure==null?"passed":"failed");if(failure!=null)report.set("failure",failure.toString());
        report.set("limits",List.of("Dedicated in-memory RoomRuntimeService and native Paper inventory/item/event objects; production rooms/configuration are untouched.","Player, InventoryView, world copying and match staging are interface recorders. No native client click packets, visual title rendering or full match are claimed.","Countdown is driven through its registered service task. The Paper scheduler still dispatches actual delayed menu click callbacks."));
        var file=plugin.getDataFolder().toPath().resolve("rc11/voting-report.yml");
        try{AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));}
        catch(Throwable save){if(failure==null)failure=save;else failure.addSuppressed(save);}
        busy=false;
        if(failure==null)sender.sendMessage("RC11 VOTING SUCCESS native-inventory=OK owner=OK stale-click=OK language=OK countdown-freeze=OK path="+file.toAbsolutePath());
        else{sender.sendMessage("RC11 VOTING FAILED "+failure);plugin.getLogger().log(Level.SEVERE,"RC11 voting probe failed",failure);}
    }
    private static String plain(Component component){return PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(component));}
    private static void rejected(Runnable action,String message){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new IllegalStateException("RC11 voting assertion: "+message);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("RC11 voting assertion: "+message);}
    private final class Viewer {
        final UUID id=UUID.randomUUID();final Player player;final List<Component> messages=new ArrayList<>();final Inventory bottom=Bukkit.createInventory(null,36);
        Locale locale;InventoryView view;boolean permitted=true,dead,closed;
        Viewer(Locale locale){
            this.locale=locale;
            player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->switch(method.getName()){
                case "getUniqueId"->id;case "hasPermission"->permitted;case "isDead"->dead;case "isOnline"->true;case "locale"->this.locale;
                case "getOpenInventory"->view;
                case "openInventory"->{require(args[0] instanceof Inventory,"Expected native Inventory open");show((Inventory)args[0]);yield view;}
                case "closeInventory"->{show(Bukkit.createInventory(null,9));closed=true;yield null;}
                case "sendMessage"->{for(Object arg:args)if(arg instanceof Component component)messages.add(component);yield null;}
                case "equals"->self==args[0];case "hashCode"->id.hashCode();case "toString"->"Rc11VotingPlayerInterfaceRecorder";
                default->throw new IllegalStateException("Unexpected voting Player API "+method);
            });
            show(Bukkit.createInventory(null,9));viewers.put(id,this);
        }
        Inventory top(){return view.getTopInventory();}
        void show(Inventory inventory){
            closed=false;
            view=(InventoryView)Proxy.newProxyInstance(InventoryView.class.getClassLoader(),new Class<?>[]{InventoryView.class},(self,method,args)->switch(method.getName()){
                case "getTopInventory"->inventory;case "getBottomInventory"->bottom;case "getPlayer"->player;case "getType"->InventoryType.CHEST;
                case "getInventory"->{int slot=(int)args[0];yield slot<0?null:slot<inventory.getSize()?inventory:bottom;}
                case "convertSlot"->{int slot=(int)args[0];yield slot<inventory.getSize()?slot:slot-inventory.getSize();}
                case "getSlotType"->InventoryType.SlotType.CONTAINER;case "countSlots"->inventory.getSize()+bottom.getSize();
                case "getCursor"->null;case "getTitle","getOriginalTitle"->"Interface recorder";case "title"->Component.text("Interface recorder");
                case "equals"->self==args[0];case "hashCode"->System.identityHashCode(self);case "toString"->"Rc11VotingInventoryViewInterfaceRecorder";
                default->throw new IllegalStateException("Unexpected voting InventoryView API "+method);
            });
        }
    }
    private static final class Clockwork implements GameScheduler {
        private final List<Scheduled> tasks=new ArrayList<>();
        public Task repeat(int periodTicks,Runnable action){var task=new Scheduled(action);tasks.add(task);return task;}
        void pulse(int seconds){for(int i=0;i<seconds;i++)for(var task:List.copyOf(tasks))if(!task.cancelled)task.action.run();}
        private static final class Scheduled implements Task {
            final Runnable action;boolean cancelled;Scheduled(Runnable action){this.action=action;}
            public void cancel(){cancelled=true;}
        }
    }
}