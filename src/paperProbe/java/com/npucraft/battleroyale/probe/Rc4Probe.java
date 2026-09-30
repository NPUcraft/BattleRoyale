package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.LobbySettings;
import com.npucraft.battleroyale.config.LobbySidebarSettings;
import com.npucraft.battleroyale.paper.LobbySidebarModel;
import com.npucraft.battleroyale.paper.PaperLobbySidebar;
import com.npucraft.battleroyale.service.ChineseMessages;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import com.npucraft.battleroyale.paper.LobbyBlueprint;
import com.npucraft.battleroyale.paper.NativeItemSerializer;
import com.npucraft.battleroyale.paper.PaperLobby;
import com.npucraft.battleroyale.paper.QueueExitPayload;
import com.npucraft.battleroyale.session.GameState;
import com.npucraft.battleroyale.service.UiText;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Isolated rc4 migration checks. Requires -Dbattleroyale.probe.rc4=true and is never shipped in BattleRoyale.jar. */
public final class Rc4Probe {
    private static final Set<String> LABELS = Set.of("solo", "duo", "squad", "welcome");
    private static final String FOREIGN = "rc4_foreign";
    private final JavaPlugin plugin;
    private boolean busy;
    public Rc4Probe(JavaPlugin plugin) { this.plugin = plugin; }

    public void command(CommandSender sender, String[] args) {
        require(Boolean.getBoolean("battleroyale.probe.rc4"), "Explicit isolated rc4 probe flag required");
        if (busy) throw new IllegalStateException("RC4 probe already running");
        if (args.length != 2 || !Set.of("prepare", "verify", "styles", "items", "sidebar").contains(args[1]))
            throw new IllegalArgumentException("p26rc4 prepare | verify | styles | items | sidebar");
        busy = true;
        CompletableFuture<String> work;
        try {
            work = switch(args[1]) {
                case "styles" -> styles();
                case "items" -> items();
                case "sidebar" -> sidebar();
                default -> lobby(args[1].equals("prepare"));
            };
        } catch (Exception error) { work = CompletableFuture.failedFuture(error); }
        work.whenComplete((detail, error) -> main(() -> {
            busy = false;
            if (error == null) sender.sendMessage("RC4 " + args[1].toUpperCase(Locale.ROOT) + " SUCCESS " + detail);
            else {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                sender.sendMessage("RC4 " + args[1].toUpperCase(Locale.ROOT) + " FAILED " + cause);
                plugin.getLogger().log(Level.SEVERE, "RC4 " + args[1] + " FAILED", cause);
            }
            return null;
        }));
    }
    private Plugin battleRoyale() { return Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale")); }
    private Path data() { return battleRoyale().getDataFolder().toPath().toAbsolutePath().normalize(); }
    private Path output(String name) { return plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("rc4").resolve(name); }
    private CompletableFuture<String> save(String filename, YamlConfiguration yaml) {
        Path target = output(filename); byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        return CompletableFuture.supplyAsync(() -> {
            try { AtomicFiles.write(target, bytes); return target.toString(); }
            catch (Exception error) { throw new CompletionException(error); }
        });
    }
    private CompletableFuture<String> styles() {
        var report = new YamlConfiguration();
        String joined = "已加入房间 solo，使用 /br leave 返回大厅。";
        Component chat = UiText.message(joined);
        require(plain(chat).equals("[BattleRoyale] " + joined), "Chat plain text preserved");
        var chatRuns = runs(chat); assertNoEvents(chat);
        require(colors(chatRuns).containsAll(Set.of(UiText.BRAND.asHexString(), UiText.SUCCESS.asHexString(), UiText.VALUE.asHexString())), "Chat prefix, successful action and command have colors");
        require(chatRuns.stream().anyMatch(r -> r.get("text").equals("BattleRoyale") && r.get("bold").equals(true)), "Chat prefix bold");
        require(chatRuns.stream().anyMatch(r -> r.get("text").toString().contains("/br leave") && r.get("bold").equals(true)), "Command bold");
        Component status = UiText.text("存活：3 | 击杀：2"); assertNoEvents(status);
        require(colors(runs(status)).containsAll(Set.of(UiText.SUCCESS.asHexString(), UiText.VALUE.asHexString())), "Status and numbers colored");
        String literal = "<click:run_command:'/op probe'> &c §c <red>名字</red>";
        Component untrusted = UiText.text(literal); assertNoEvents(untrusted);
        require(plain(untrusted).equals(literal), "Markup-like input is literal and cannot create events");
        require(runs(UiText.heading("房间列表")).stream().allMatch(r -> r.get("bold").equals(true)), "Heading bold");
        require(colors(runs(UiText.error("错误"))).contains(UiText.ERROR.asHexString()), "Error tone");
        require(colors(runs(UiText.warning("请稍候"))).contains(UiText.WARNING.asHexString()), "Warning tone");
        report.set("platform", plugin.getServer().getVersion()); report.set("chat", chatRuns);
        report.set("status", runs(status)); report.set("literal", plain(untrusted)); report.set("events", "none");
        report.set("limits", "Component API assertions only; not a native player client rendering or click test.");
        return save("styles-report.yml", report).thenApply(path -> "chatColors=OK literalInput=OK events=none path=" + path);
    }
    private CompletableFuture<String> items() {
        UUID owner=UUID.randomUUID(),session=UUID.randomUUID();
        var original=new ItemStack(Material.DIAMOND_SWORD);
        var meta=original.getItemMeta();meta.displayName(Component.text("原始物品 <red> &c",NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(Component.text("Native metadata must survive",NamedTextColor.AQUA)));
        meta.addEnchant(Enchantment.SHARPNESS,3,false);((Damageable)meta).setDamage(17);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"original_fixture"),PersistentDataType.STRING,"retained");original.setItemMeta(meta);
        var before=original.clone();
        var bed=PaperLobby.createQueueExitItem(battleRoyale(),owner,session,original);
        require(original.equals(before),"Factory does not mutate original slot item");
        require(bed.getType()==Material.RED_BED && bed.getAmount()==1,"Actual exit factory RED_BED");
        require(plain(bed.getItemMeta().displayName()).equals("退出房间"),"Exit bed Chinese display name");
        require(colors(runs(bed.getItemMeta().displayName())).contains(NamedTextColor.RED.asHexString()),"Exit bed name color");
        var nativeItems=new NativeItemSerializer();var stored=nativeItems.store(bed);var decoded=nativeItems.item(stored);
        require(decoded.equals(bed),"Complete exit bed native item roundtrip");
        var pdc=decoded.getItemMeta().getPersistentDataContainer();
        require("leave".equals(pdc.get(new NamespacedKey(battleRoyale(),"lobby_action"),PersistentDataType.STRING)),"Real bed action PDC");
        var payload=QueueExitPayload.decode(pdc.get(new NamespacedKey(battleRoyale(),"queue_exit_original"),PersistentDataType.BYTE_ARRAY));
        require(payload.belongsTo(owner,session),"Owner/session survive native item bytes");
        require(!payload.belongsTo(UUID.randomUUID(),session)&&!payload.belongsTo(owner,UUID.randomUUID()),"Other owner/session rejected");
        require(ItemStack.deserializeBytes(payload.original()).equals(before),"Displaced item including enchantments/damage/name/lore/PDC restored exactly");
        var empty=PaperLobby.createQueueExitItem(battleRoyale(),owner,session,null);
        require(QueueExitPayload.decode(empty.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(battleRoyale(),"queue_exit_original"),PersistentDataType.BYTE_ARRAY)).original().length==0,"Empty original slot");
        byte[] corrupted=payload.encode();corrupted[corrupted.length-1]^=1;
        boolean rejected=false;try{QueueExitPayload.decode(corrupted);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"Corrupt envelope rejected before item replacement");
        var states=new LinkedHashMap<String,Boolean>();
        for(var state:GameState.values()) {
            boolean allowed=QueueExitPayload.queue(state);states.put(state.name(),allowed);
            require(allowed==(state==GameState.WAITING||state==GameState.COUNTDOWN),"Queue state gate "+state);
        }
        var report=new YamlConfiguration();report.set("factory","PaperLobby.createQueueExitItem");
        report.set("material",decoded.getType().name());report.set("name",plain(decoded.getItemMeta().displayName()));
        report.set("name-runs",runs(decoded.getItemMeta().displayName()));report.set("action","leave");
        report.set("native-format",stored.format());report.set("native-version",stored.version());
        report.set("owner",owner.toString());report.set("session",session.toString());
        report.set("original","DIAMOND_SWORD sharpness3 damage17 name/lore/customPDC exact equality");report.set("queue-states",states);
        report.set("corrupt-payload","rejected");report.set("limits","Real ItemStack factory and state predicate only; no synthetic Player or click event claimed.");
        return save("items-report.yml",report).thenApply(path->"nativeBedPdc=OK originalItem=exact queueStates=WAITING,COUNTDOWN path="+path);
    }
    private CompletableFuture<String> sidebar() {
        var configured=LobbySidebarSettings.load(YamlConfiguration.loadConfiguration(data().resolve("lobby.yml").toFile()));
        require(configured.enabled(),"Fixture lobby sidebar enabled, including legacy config defaults");
        var manager=Objects.requireNonNull(plugin.getServer().getScoreboardManager());
        var original=manager.getNewScoreboard();
        original.registerNewObjective("rc4_original",Criteria.DUMMY,Component.text("Original scoreboard"));
        var foreign=manager.getNewScoreboard();
        foreign.registerNewObjective("rc4_foreign",Criteria.DUMMY,Component.text("Another plugin scoreboard"));
        var holder=new SidebarPlayer(original);var player=holder.player();
        var errors=new ArrayList<Throwable>();
        var sidebar=new PaperLobbySidebar(manager,new LobbySidebarSettings(true,"大逃杀",8),errors::add);
        var report=new YamlConfiguration();
        report.set("platform",plugin.getServer().getVersion());
        report.set("mode","Real Paper Scoreboard/Objective/Team/Score objects with a Player interface proxy; no connected client or scoreboard packets.");
        report.set("configured.enabled",configured.enabled());report.set("configured.title",configured.title());report.set("configured.page-seconds",configured.pageSeconds());
        try {
            var rooms=List.of(
                    new LobbySidebarModel.RoomView("solo","单人竞技",0,24,2,GameState.WAITING,-1),
                    new LobbySidebarModel.RoomView("duo","双人组队",3,24,4,GameState.WAITING,-1),
                    new LobbySidebarModel.RoomView("squad","四人小队",8,32,8,GameState.COUNTDOWN,12));
            var first=LobbySidebarModel.page(rooms,11,0,8);
            sidebar.update(player,true,first);
            var owned=holder.current;
            require(owned!=original,"Eligible audience receives its owned sidebar");
            require(first.lines().size()==5,"Three-room compact page contains online, three rooms, and page footer");
            require(holder.assignments==1,"Initial board assigned once");
            sidebarFrame(owned,first,"initial",report,"大逃杀");
            String initial=String.join("\n",report.getStringList("frames.initial.lines"));
            require(initial.contains("单人竞技")&&initial.contains("双人组队")&&initial.contains("四人小队"),"Every configured room shown");
            require(initial.replace(" ","").contains("0/24")&&initial.replace(" ","").contains("8/32"),"Current players/capacity shown");
            require(initial.contains("12秒"),"Countdown 12 seconds rendered");
            var nextRooms=new ArrayList<>(rooms);
            nextRooms.set(2,new LobbySidebarModel.RoomView("squad","四人小队",8,32,8,GameState.COUNTDOWN,11));
            var next=LobbySidebarModel.page(nextRooms,11,1,8);
            sidebar.update(player,true,next);
            require(holder.current==owned&&holder.assignments==1,"State update reuses assigned board without setScoreboard churn");
            sidebarFrame(owned,next,"countdown11",report,"大逃杀");
            String countdown=String.join("\n",report.getStringList("frames.countdown11.lines"));
            require(countdown.contains("11")&&!countdown.contains("12"),"Countdown changed from 12 to 11");
            nextRooms.set(2,new LobbySidebarModel.RoomView("squad","四人小队",6,32,8,GameState.RUNNING,-1));
            var running=LobbySidebarModel.page(nextRooms,9,2,8);
            sidebar.update(player,true,running);sidebarFrame(owned,running,"running",report,"大逃杀");
            String runningText=String.join("\n",report.getStringList("frames.running.lines"));
            require(runningText.contains("进行中")&&!runningText.contains("12秒"),"Running replaces countdown state");
            for(var state:List.of(GameState.PREPARING,GameState.STARTING,GameState.ENDING,GameState.CLEANUP)) {
                var page=LobbySidebarModel.page(List.of(new LobbySidebarModel.RoomView("solo","单人竞技",2,24,2,state,-1)),2,0,8);
                sidebar.update(player,true,page);sidebarFrame(owned,page,state.name(),report,"大逃杀");
                require(String.join("\n",report.getStringList("frames."+state.name()+".lines")).contains(switch(state){case PREPARING,STARTING->"准备";case ENDING->"结算";case CLEANUP->"清理";default->throw new IllegalStateException("Unexpected phase");}),"Phase text "+state);
            }
            var many=new ArrayList<LobbySidebarModel.RoomView>();
            for(int i=0;i<12;i++)many.add(new LobbySidebarModel.RoomView("room_"+i,"房间"+i,i,24,2,GameState.WAITING,-1));
            var secondPage=LobbySidebarModel.page(many,66,8,8);
            require(secondPage.lines().size()==7,"Five-room page has seven compact lines");
            require(secondPage.number()==2&&secondPage.total()==3,"Five rooms per page and timed page switch");
            sidebar.update(player,true,secondPage);sidebarFrame(owned,secondPage,"page2",report,"大逃杀");
            var thirdPage=LobbySidebarModel.page(many,66,16,8);
            require(thirdPage.lines().size()==4,"Two-room page has four compact lines");
            require(thirdPage.number()==3&&thirdPage.total()==3,"Last page reached after sixteen seconds");
            sidebar.update(player,true,thirdPage);sidebarFrame(owned,thirdPage,"page3",report,"大逃杀");
            require(thirdPage.lines().size()<secondPage.lines().size(),"Short last page removes stale score rows");
            var repeated=new LobbySidebarModel.Page(List.of(Component.text("重复"),Component.empty(),Component.text("重复")),1,1);
            sidebar.update(player,true,repeated);sidebarFrame(owned,repeated,"repeated",report,"大逃杀");
            require(report.getStringList("frames.repeated.lines").equals(List.of("重复","","重复")),"Duplicate and blank visible rows survive using distinct entries");
            var audienceStates=new LinkedHashMap<String,Boolean>();
            for(GameState state:Arrays.asList(null,GameState.WAITING,GameState.COUNTDOWN,GameState.PREPARING,GameState.STARTING,GameState.RUNNING,GameState.ENDING,GameState.CLEANUP)) {
                boolean visible=LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,false,false,state));
                boolean expected=state==null||state==GameState.WAITING||state==GameState.COUNTDOWN;
                require(visible==expected,"Audience state "+state);audienceStates.put(state==null?"LOBBY":state.name(),visible);
                sidebar.update(player,visible,first);require((holder.current!=original)==visible,"Audience state drives real controller assignment/restoration");
            }
            for(var audience:List.of(
                    new LobbySidebarModel.Audience(false,true,false,false,false,false,false,null),
                    new LobbySidebarModel.Audience(true,false,false,false,false,false,false,null),
                    new LobbySidebarModel.Audience(true,true,true,false,false,false,false,null),
                    new LobbySidebarModel.Audience(true,true,false,true,false,false,false,null),
                    new LobbySidebarModel.Audience(true,true,false,false,true,false,false,null),
                    new LobbySidebarModel.Audience(true,true,false,false,false,true,false,null),
                    new LobbySidebarModel.Audience(true,true,false,false,false,false,true,null)))require(!LobbySidebarModel.visible(audience),"Unsafe/non-lobby audience hidden");
            report.set("audience-states",audienceStates);report.set("audience-denials",List.of("not ready","other world","dead","pending restore","editor","frozen","spectator"));
            sidebar.update(player,false,first);
            require(holder.current==original,"Leaving sidebar eligibility restores previous board");
            sidebar.update(player,true,first);require(holder.current!=original,"Reentering receives owned board");
            holder.current=foreign;
            int assignedBeforeForeign=holder.assignments;
            sidebar.update(player,true,next);sidebar.update(player,true,next);
            require(holder.current==foreign&&holder.assignments==assignedBeforeForeign,"Another plugin takeover is not overwritten on repeated update");
            sidebar.configure(new LobbySidebarSettings(true,"重载标题",8));sidebar.suspend(player);sidebar.update(player,true,next);
            require(holder.current==foreign&&holder.assignments==assignedBeforeForeign,"Configuration reload plus temporary suspension preserves foreign takeover suppression");
            sidebar.configure(new LobbySidebarSettings(false,"重载标题",8));sidebar.update(player,true,next);
            sidebar.configure(new LobbySidebarSettings(true,"重载标题",8));sidebar.update(player,true,next);
            require(holder.current==foreign&&holder.assignments==assignedBeforeForeign,"Disable/enable preserves foreign takeover suppression");
            sidebar.hide(player);require(holder.current==foreign,"Hide preserves foreign board");
            sidebar.update(player,true,first);holder.current=foreign;
            int unobservedAssignments=holder.assignments;sidebar.suspend(player);sidebar.update(player,true,next);
            require(holder.current==foreign&&holder.assignments==unobservedAssignments,"Unobserved foreign takeover survives suspension and next update");
            sidebar.hide(player);
            sidebar.update(player,true,first);require(holder.current!=foreign,"New eligible visit can attach after hide");
            sidebar.retain(Set.of());require(holder.current==foreign,"Retain cleanup restores captured previous board");
            sidebar.update(player,true,first);
            sidebar.configure(new LobbySidebarSettings(false,"大逃杀",8));
            require(holder.current==foreign,"Disabling sidebar restores previous board");
            sidebar.update(player,true,first);require(holder.current==foreign,"Disabled sidebar does not assign");
            sidebar.configure(new LobbySidebarSettings(true,"测试侧边栏",8));
            sidebar.update(player,true,first);sidebarFrame(holder.current,first,"configured",report,"测试侧边栏");
            sidebar.close();require(holder.current==foreign,"Close restores previous board only while still owned");
            require(original.getObjective("rc4_original")!=null&&foreign.getObjective("rc4_foreign")!=null,"Original/foreign objectives untouched");
            require(errors.isEmpty(),"Sidebar controller reported no errors: "+errors);
            report.set("ownership",List.of("eligible assignment","stable update","eligibility restore","foreign takeover suppression","reload/temporary-gate suppression","disabled-toggle suppression","unobserved takeover suspension","hide preservation","retain cleanup","disabled suppression","configure title","close restore"));
            report.set("player-proxy-assignments",holder.assignments);report.set("errors",List.of());
            report.set("limits","Player is an interface proxy only; actual player joins, packets, visible sidebar rendering and client transitions are not asserted.");
        } finally {
            sidebar.close();
            for(var board:List.of(original,foreign))for(var objective:Set.copyOf(board.getObjectives()))objective.unregister();
        }
        return save("sidebar-report.yml",report).thenApply(path->"paperScoreboard=OK liveStateAndCountdown=OK paging=OK hiddenNumbers=OK proxyOwnership=OK path="+path);
    }
    private static void sidebarFrame(Scoreboard board,LobbySidebarModel.Page page,String phase,YamlConfiguration report,String title) {
        var objective=Objects.requireNonNull(board.getObjective("br_lobby"),"Sidebar objective");
        require(objective.getDisplaySlot()==DisplaySlot.SIDEBAR,"Sidebar display slot");
        require(plain(objective.displayName()).equals(title),"Configured Adventure title");
        require(Objects.equals(objective.numberFormat(),NumberFormat.blank()),"Objective numbers hidden via Paper blank format");
        require(board.getObjectives().size()==1,"Exactly one owned objective");
        var scores=board.getEntries().stream().map(objective::getScore).filter(Score::isScoreSet)
                .sorted(Comparator.comparingInt(Score::getScore).reversed()).toList();
        require(scores.size()==page.lines().size()&&scores.size()<=15,"Visible score count equals page lines, maximum fifteen");
        require(scores.stream().map(Score::getScore).distinct().count()==scores.size(),"Unique ordering scores");
        var actual=new ArrayList<String>();var entries=new ArrayList<String>();
        for(var score:scores) {
            require(score.numberFormat()==null||Objects.equals(score.numberFormat(),NumberFormat.blank()),"No line overrides hidden numbers");
            var team=Objects.requireNonNull(board.getEntryTeam(score.getEntry()),"One team per rendered entry");
            Component content=score.customName()==null?Component.empty().append(team.prefix()).append(team.suffix()):score.customName();
            actual.add(plain(content));entries.add(score.getEntry());
        }
        var expected=page.lines().stream().map(Rc4Probe::plain).toList();
        require(actual.equals(expected),"Actual Paper lines match rendered page order: "+phase+" actual="+actual+" expected="+expected);
        require(new HashSet<>(entries).size()==entries.size(),"Distinct entries even when visible text repeats");
        report.set("frames."+phase+".title",plain(objective.displayName()));report.set("frames."+phase+".title-runs",runs(objective.displayName()));
        report.set("frames."+phase+".lines",actual);report.set("frames."+phase+".line-count",actual.size());
        report.set("frames."+phase+".page",page.number());report.set("frames."+phase+".pages",page.total());
        report.set("frames."+phase+".number-format",objective.numberFormat().getClass().getSimpleName());
    }
    private final class SidebarPlayer implements java.lang.reflect.InvocationHandler {
        private final UUID id=UUID.randomUUID();private Scoreboard current;private int assignments;
        private SidebarPlayer(Scoreboard original){current=original;}
        private Player player(){return (Player)java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},this);}
        public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] arguments) {
            return switch(method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Rc4SidebarProxy";
                case "locale" -> Locale.SIMPLIFIED_CHINESE;
                case "getScoreboard" -> current;
                case "setScoreboard" -> {current=Objects.requireNonNull((Scoreboard)arguments[0]);assignments++;yield null;}
                case "isOnline" -> true;
                case "isDead" -> false;
                case "getServer" -> plugin.getServer();
                case "toString" -> "Rc4SidebarPlayerInterfaceProxy["+id+"]";
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy==arguments[0];
                default -> throw new UnsupportedOperationException("Sidebar proxy does not implement "+method);
            };
        }
    }
    private CompletableFuture<String> lobby(boolean prepare) throws Exception {
        var yaml = new YamlConfiguration(); yaml.load(data().resolve("lobby.yml").toFile());
        var settings = LobbySettings.load(yaml); require(settings.buildEnabled(), "Enabled built lobby required");
        World world = Objects.requireNonNull(plugin.getServer().getWorld(settings.world()), "Lobby world");
        var marker = new Properties();
        try (var input = Files.newInputStream(data().resolve("lobby-structure.properties"))) { marker.load(input); }
        require(marker.getProperty("state").equals("READY"), "READY marker");
        require(world.getUID().toString().equals(marker.getProperty("world-uuid")), "Marker world identity");
        var tickets = new ArrayList<Chunk>(); var pending = new ArrayList<CompletableFuture<?>>();
        for (int x = Math.floorDiv(settings.x()-32,16); x <= Math.floorDiv(settings.x()+32,16); x++)
            for (int z = Math.floorDiv(settings.z()-32,16); z <= Math.floorDiv(settings.z()+32,16); z++)
                pending.add(world.getChunkAtAsync(x,z,false).thenCompose(chunk -> main(() -> {
                    require(chunk != null, "Existing lobby chunk"); chunk.addPluginChunkTicket(plugin); tickets.add(chunk); return null;
                })));
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenCompose(unused -> blocks(world, settings))
                .thenCompose(blockHash -> main(() -> prepare ? prepare(world, settings, blockHash) : verify(world, blockHash)))
                .thenCompose(report -> save(prepare ? "baseline.yml" : "lobby-report.yml", report))
                .thenApply(path -> (prepare ? "whiteLabels=4 foreignFixture=created" : "labels=4 foreignPreserved=true blocks=unchanged") + " path=" + path)
                .whenComplete((unused,error) -> main(() -> { tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin)); return null; }));
    }
    private YamlConfiguration prepare(World world, LobbySettings settings, String blockHash) throws Exception {
        require(!Files.exists(output("baseline.yml")), "Fresh fixture required; baseline already exists");
        var labels = labels(world); require(labels.keySet().equals(LABELS), "Exactly four known owned labels before fixture");
        var report = new YamlConfiguration();
        report.set("world", world.getName()); report.set("world-uuid", world.getUID().toString());
        report.set("blocks-sha256", blockHash); report.set("blueprint-positions", LobbyBlueprint.blocks().size());
        report.set("marker-sha256", hash(data().resolve("lobby-structure.properties")));
        report.set("backup-sha256", hash(data().resolve("lobby-structure-original.blocks.gz")));
        for (var entry : labels.entrySet()) {
            require(entry.getValue().size()==1, "Single original label " + entry.getKey());
            TextDisplay label = entry.getValue().getFirst(); String prefix = "labels." + entry.getKey();
            report.set(prefix + ".uuid", label.getUniqueId().toString()); report.set(prefix + ".text", plain(label.text()));
            report.set(prefix + ".position", position(label));
            label.text(Component.text(plain(label.text()), NamedTextColor.WHITE));
            require(colors(runs(label.text())).equals(Set.of(NamedTextColor.WHITE.asHexString())), "Old white fixture");
        }
        var key = new NamespacedKey(battleRoyale(),"lobby_structure_label");
        require(world.getEntitiesByClass(TextDisplay.class).stream().noneMatch(d -> FOREIGN.equals(d.getPersistentDataContainer().get(key,PersistentDataType.STRING))), "No existing foreign test label");
        var foreign = world.spawn(new Location(world,settings.x()+8.5,settings.y()+4,settings.z()+8.5),TextDisplay.class,display -> {
            display.text(Component.text("RC4 foreign label: keep unchanged",NamedTextColor.RED)); display.setPersistent(true);
            display.getPersistentDataContainer().set(key,PersistentDataType.STRING,FOREIGN);
            display.getPersistentDataContainer().set(new NamespacedKey(plugin,"rc4_owned_fixture"),PersistentDataType.BYTE,(byte)1);
        });
        report.set("foreign.uuid", foreign.getUniqueId().toString()); report.set("foreign.text", plain(foreign.text()));
        report.set("foreign.position", position(foreign)); report.set("foreign.colors", new ArrayList<>(colors(runs(foreign.text()))));
        world.save(); return report;
    }
    private YamlConfiguration verify(World world, String blockHash) throws Exception {
        var baseline = new YamlConfiguration(); baseline.load(output("baseline.yml").toFile());
        require(world.getUID().toString().equals(baseline.getString("world-uuid")), "World UUID unchanged");
        require(blockHash.equals(baseline.getString("blocks-sha256")), "Every lobby blueprint block data unchanged");
        require(hash(data().resolve("lobby-structure.properties")).equals(baseline.getString("marker-sha256")), "READY marker unchanged");
        require(hash(data().resolve("lobby-structure-original.blocks.gz")).equals(baseline.getString("backup-sha256")), "Construction backup unchanged");
        var labels = labels(world); require(labels.keySet().equals(LABELS), "All four known labels");
        var report = new YamlConfiguration(); report.set("world",world.getName()); report.set("blocks-sha256",blockHash);
        report.set("blueprint-positions",LobbyBlueprint.blocks().size()); report.set("marker-and-backup","unchanged");
        for (var entry : labels.entrySet()) {
            require(entry.getValue().size()==1, "No duplicate known labels " + entry.getKey());
            var label = entry.getValue().getFirst(); String prefix="labels."+entry.getKey();
            require(label.getUniqueId().toString().equals(baseline.getString(prefix+".uuid")), "Existing label UUID retained: " + entry.getKey());
            require(plain(label.text()).equals(baseline.getString(prefix+".text")), "Plain label text retained: " + entry.getKey());
            require(position(label).equals(baseline.getDoubleList(prefix+".position")), "Label position retained");
            var parts=runs(label.text());
            require(colors(parts).size()>=3, "Heading/body/action use distinct colors: " + entry.getKey());
            require(parts.stream().anyMatch(r->r.get("bold").equals(true)), "Bold heading: " + entry.getKey());
            assertNoEvents(label.text());
            report.set(prefix+".uuid",label.getUniqueId().toString()); report.set(prefix+".text",plain(label.text()));
            report.set(prefix+".runs",parts); report.set(prefix+".position",position(label));
        }
        var foreign=world.getEntitiesByClass(TextDisplay.class).stream().filter(d->d.getUniqueId().toString().equals(baseline.getString("foreign.uuid"))).findFirst().orElseThrow();
        require(FOREIGN.equals(foreign.getPersistentDataContainer().get(new NamespacedKey(battleRoyale(),"lobby_structure_label"),PersistentDataType.STRING)), "Foreign label PDC unchanged");
        require(plain(foreign.text()).equals(baseline.getString("foreign.text")) && position(foreign).equals(baseline.getDoubleList("foreign.position")), "Foreign label content/location unchanged");
        require(colors(runs(foreign.text())).equals(new HashSet<>(baseline.getStringList("foreign.colors"))), "Foreign label color unchanged");
        report.set("foreign", "retained unchanged"); report.set("foreign-uuid",foreign.getUniqueId().toString());
        // Remove only the explicitly probe-owned test entity after all preservation assertions pass.
        require(foreign.getPersistentDataContainer().has(new NamespacedKey(plugin,"rc4_owned_fixture"),PersistentDataType.BYTE), "Owned test entity before cleanup");
        foreign.remove(); report.set("foreign-fixture-cleaned",true); return report;
    }
    private Map<String,List<TextDisplay>> labels(World world) {
        var result=new TreeMap<String,List<TextDisplay>>(); var key=new NamespacedKey(battleRoyale(),"lobby_structure_label");
        for(var label:world.getEntitiesByClass(TextDisplay.class)) {
            String id=label.getPersistentDataContainer().get(key,PersistentDataType.STRING);
            if(id!=null&&LABELS.contains(id))result.computeIfAbsent(id,unused->new ArrayList<>()).add(label);
        }
        return result;
    }
    private CompletableFuture<String> blocks(World world, LobbySettings settings) {
        var future=new CompletableFuture<String>(); var plan=LobbyBlueprint.blocks();
        final MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");} catch(Exception error){return CompletableFuture.failedFuture(error);}
        new BukkitRunnable() {
            int cursor;
            public void run(){try {
                for(int count=0;count<1500&&cursor<plan.size();count++,cursor++) {
                    var p=plan.get(cursor).position();String line=p.x()+","+p.y()+","+p.z()+":"
                            +world.getBlockAt(settings.x()+p.x(),settings.y()+p.y(),settings.z()+p.z()).getBlockData().getAsString()+"\n";
                    digest.update(line.getBytes(StandardCharsets.UTF_8));
                }
                if(cursor==plan.size()){cancel();future.complete(HexFormat.of().formatHex(digest.digest()));}
            }catch(Throwable error){cancel();future.completeExceptionally(error);}}
        }.runTaskTimer(plugin,1,1);
        return future;
    }
    private static List<Double> position(TextDisplay display) {var p=display.getLocation();return List.of(p.getX(),p.getY(),p.getZ(),(double)p.getYaw(),(double)p.getPitch());}
    private static String plain(Component value) {return PlainTextComponentSerializer.plainText().serialize(value);}
    private static List<Map<String,Object>> runs(Component component) {var result=new ArrayList<Map<String,Object>>();collect(component,null,false,result);return result;}
    private static void collect(Component component,TextColor inherited,boolean inheritedBold,List<Map<String,Object>> result) {
        TextColor color=component.color()==null?inherited:component.color();
        boolean bold=switch(component.decoration(TextDecoration.BOLD)){case TRUE->true;case FALSE->false;case NOT_SET->inheritedBold;};
        if(component instanceof TextComponent text&&!text.content().isBlank())result.add(Map.of("text",text.content(),"color",color==null?"default":color.asHexString(),"bold",bold));
        component.children().forEach(child->collect(child,color,bold,result));
    }
    private static Set<String> colors(List<Map<String,Object>> runs) {var colors=new TreeSet<String>();runs.forEach(run->colors.add(run.get("color").toString()));return colors;}
    private static void assertNoEvents(Component component) {require(component.clickEvent()==null&&component.hoverEvent()==null&&component.insertion()==null,"No generated interactive text events");component.children().forEach(Rc4Probe::assertNoEvents);}
    private static String hash(Path file)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private <T> CompletableFuture<T> main(Callable<T> action) {
        var future=new CompletableFuture<T>();Runnable run=()->{try{future.complete(action.call());}catch(Throwable error){future.completeExceptionally(error);}};
        if(Bukkit.isPrimaryThread())run.run();else plugin.getServer().getScheduler().runTask(plugin,run);return future;
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("RC4 assertion: "+message);}
}
