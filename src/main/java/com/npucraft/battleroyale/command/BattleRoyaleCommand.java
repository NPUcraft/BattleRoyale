package com.npucraft.battleroyale.command;
import com.npucraft.battleroyale.service.*;
import com.npucraft.battleroyale.config.ConfigurationException;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Permission/routing adapter; lifecycle and filesystem work stay in services. */
public final class BattleRoyaleCommand implements CommandExecutor, TabCompleter {
    private final FoundationService foundation;
    private final PluginRuntime runtime;
    private final MessageService messages;
    private final String version;
    public BattleRoyaleCommand(FoundationService foundation, PluginRuntime runtime, MessageService messages, String version) {
        this.foundation = foundation; this.runtime = runtime; this.messages = messages; this.version = version;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("battleroyale.command")) { messages.denied(sender); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (Set.of("reload", "debug").contains(action) && !sender.hasPermission("battleroyale.admin")
                || Set.of("rooms", "join", "autojoin", "leave", "team", "spectate", "lobby", "profile", "leaderboard", "shop", "cosmetics", "vote", "economy").contains(action) && !sender.hasPermission("battleroyale.play")) {
            messages.denied(sender); return true;
        }
        try {
            var rooms = runtime.rooms();
            switch (action) {
                case "lobby", "profile", "leaderboard", "shop", "cosmetics", "vote" -> {
                    if(!(sender instanceof Player player))throw new IllegalArgumentException(I18n.text(sender, "此命令只能由游戏内玩家执行。", "Only in-game players can use this command."));
                    runtime.lobby().command(player,action,args.length>1?args[1]:null);
                }
                case "admin" -> {
                    String required=args.length>1?switch(args[1].toLowerCase(Locale.ROOT)){case "map"->"battleroyale.admin.map";case "config"->"battleroyale.admin.config";case "cosmetic","purchases"->"battleroyale.admin.cosmetic";case "diagnose","supportbundle"->"battleroyale.admin.diagnostics";default->"battleroyale.admin";}:"battleroyale.admin";
                    if(!sender.hasPermission(required)){messages.denied(sender);break;}
                    if(args.length>=2 && Set.of("diagnose","supportbundle","config").contains(args[1].toLowerCase(Locale.ROOT))){runtime.diagnostics().command(sender,args);break;}
                    if(args.length>=2 && args[1].equalsIgnoreCase("map")){runtime.mapAdministration().command(sender,args);break;}
                    if(args.length>=2 && args[1].equalsIgnoreCase("purchases")) {
                        runtime.progression().purchases().whenComplete((rows,error)->{if(error!=null)messages.send(sender,I18n.text(sender, "暂时无法读取购买记录。", "Purchase records are temporarily unavailable."));else if(rows.isEmpty())messages.send(sender,I18n.text(sender, "没有需要人工复核的购买记录。", "No purchases need manual review."));else rows.forEach(row->messages.send(sender,row.toString()));});break;
                    }
                    if(args.length==5 && args[1].equalsIgnoreCase("cosmetic") && Set.of("grant","revoke").contains(args[2])) {
                        var target=org.bukkit.Bukkit.getOfflinePlayerIfCached(args[3]);if(target==null)throw new IllegalArgumentException(I18n.text(sender, "找不到该玩家，请填写曾加入过服务器的玩家名称。", "Player not found. Enter the name of a player who has joined this server."));
                        runtime.progression().grant(target.getUniqueId(),args[4],args[2].equals("revoke")).whenComplete((value,error)->messages.send(sender,error==null?I18n.text(sender, "外观权限已更新。", "Cosmetic access updated."):I18n.text(sender, "外观权限更新失败。", "Could not update cosmetic access.")));break;
                    }
                    if(args.length!=4 || !args[1].equalsIgnoreCase("loadout") || !args[2].equalsIgnoreCase("edit")) { messages.send(sender,I18n.text(sender, "用法：/br admin loadout edit <房间ID>", "Usage: /br admin loadout edit <room-id>")); break; }
                    if(!(sender instanceof Player player)) throw new IllegalArgumentException(I18n.text(sender, "此命令只能由游戏内玩家执行。", "Only in-game players can use this command."));
                    if(rooms.rooms().stream().map(r->rooms.session(r.id())).flatMap(Optional::stream).anyMatch(s->s.players().containsKey(player.getUniqueId())))
                        throw new IllegalStateException(I18n.text(sender, "编辑装备前请先离开房间。", "Leave your room before editing loadouts."));
                    var room=rooms.rooms().stream().filter(r->r.id().equals(args[3])).findFirst().orElseThrow(()->new IllegalArgumentException(I18n.text(sender, "房间不存在：%s", "Room not found: %s", args[3])));
                    runtime.loadouts().open(player,room,rooms.rooms());
                }
                case "economy" -> {
                    if(!(sender instanceof Player player)){messages.send(sender,"此命令只能由游戏内玩家执行。","Only in-game players can use this command.");break;}
                    var selection=runtime.progression().economy();
                    if(!selection.available()){messages.send(sender,"经济服务未就绪："+selection.diagnostics(),"Economy unavailable: "+selection.diagnostics());break;}
                    if(args.length>=2&&args[1].equalsIgnoreCase("list")){
                        if(!sender.hasPermission("battleroyale.admin")){messages.denied(sender);break;}
                        var provider=selection.provider();
                        messages.send(sender,"—— 全服经济（在线玩家，货币：%s）——","-- Server economy (online, currency: %s) --",selection.currency());
                        int count=0;double total=0;
                        for(var online:org.bukkit.Bukkit.getOnlinePlayers()){
                            try{var balance=provider.getBalance(online.getUniqueId()).doubleValue();total+=balance;count++;
                                messages.send(sender,"%s：%s","%s: %s",online.getName(),com.npucraft.battleroyale.paper.PaperEconomyRewards.format(balance));}
                            catch(LinkageError|RuntimeException error){messages.send(sender,"%s：读取失败","%s: read failed",online.getName());}
                        }
                        messages.send(sender,"共 %d 名在线玩家，合计 %s","%d online players, total %s",count,com.npucraft.battleroyale.paper.PaperEconomyRewards.format(total));
                        messages.send(sender,"离线玩家余额由经济插件自身管理。","Offline balances are managed by the economy plugin itself.");
                        break;
                    }
                    if(args.length!=1){messages.unknown(sender);break;}
                    try{var balance=selection.provider().getBalance(player.getUniqueId()).doubleValue();
                        messages.send(sender,"你的余额：%s %s","Your balance: %s %s",com.npucraft.battleroyale.paper.PaperEconomyRewards.format(balance),selection.currency());}
                    catch(LinkageError|RuntimeException error){messages.send(sender,"暂时无法读取你的余额。","Balance is temporarily unavailable.");}
                }
                case "help" -> { if (args.length <= 1) messages.help(sender); else messages.unknown(sender); }
                case "version" -> { if (args.length == 1) messages.version(sender, version); else messages.unknown(sender); }
                case "reload" -> {
                    if (args.length != 1) { messages.unknown(sender); break; }
                    try {
                        runtime.reload(); messages.loaded(foundation.state().configuration()); messages.reloaded(sender);
                    } catch (ConfigurationException | IllegalStateException error) {
                        messages.reloadError(error); messages.reloadFailed(sender, error.getMessage());
                    }
                }
                case "rooms" -> {
                    if (args.length != 1) { messages.unknown(sender); break; }
                    rooms.rooms().forEach(room -> messages.runtimeRoom(sender, room, rooms.session(room.id()).orElse(null)));
                }
                case "team" -> {
                    if(!(sender instanceof Player player))throw new IllegalArgumentException(I18n.text(sender, "此命令只能由游戏内玩家执行。", "Only in-game players can use this command."));
                    var session=rooms.participant(player.getUniqueId()).orElseThrow(()->new IllegalStateException(I18n.text(sender, "你尚未加入房间。", "You have not joined a room.")));
                    var team=session.players().get(player.getUniqueId()).teamId().map(session.teams()::get).orElseThrow(()->new IllegalStateException(I18n.text(sender, "队伍将在比赛准备、参赛名单锁定时分配。", "Teams are assigned when match preparation locks the player roster.")));
                    messages.team(sender,session,team);
                }
                case "spectate" -> {
                    if(!runtime.recoveryReady())throw new IllegalStateException(I18n.text(sender, "BattleRoyale 正在恢复比赛，请稍候。", "BattleRoyale is recovering matches. Please wait."));
                    if(!(sender instanceof Player player))throw new IllegalArgumentException(I18n.text(sender, "此命令只能由游戏内玩家执行。", "Only in-game players can use this command."));
                    if(args.length!=2)throw new IllegalArgumentException(I18n.text(sender, "用法：/br spectate <房间ID>", "Usage: /br spectate <room-id>"));
                    if(rooms.participant(player.getUniqueId()).isPresent())throw new IllegalStateException(I18n.text(sender, "你已在房间中，请先离开。", "You are already in a room. Leave it first."));
                    var session=rooms.session(args[1]).orElseThrow(()->new IllegalArgumentException(I18n.text(sender, "该房间没有正在进行的比赛。", "There is no active match in that room.")));runtime.spectators().external(player,session);
                }
                case "join", "autojoin", "leave" -> {
                    if (!(sender instanceof Player player)) { messages.send(sender, I18n.text(sender, "此命令只能由游戏内玩家执行。", "Only in-game players can use this command.")); break; }
                    if (action.equals("join") && args.length == 2) {
                        var match = rooms.spectatable(args[1]);
                        if (match.isPresent()) runtime.spectators().external(player, match.get()); else rooms.join(player.getUniqueId(), args[1]);
                    }
                    else if (action.equals("autojoin") && args.length == 1) autojoinOrSpectate(player, rooms);
                    else if (action.equals("leave") && args.length == 1) {
                        boolean ending=rooms.participant(player.getUniqueId()).map(session->session.state()==com.npucraft.battleroyale.session.GameState.ENDING).orElse(false);
                        if(ending || !runtime.spectators().leave(player.getUniqueId(),false))rooms.leave(player.getUniqueId());
                        if(ending)messages.send(player,I18n.text(sender, "已请求返回大厅并恢复原始物品；本局结算倒计时结束后可加入下一局。", "Returning to the lobby and restoring your items. You can join another match when this match settlement countdown ends."));
                    }
                    else messages.unknown(sender);
                }
                case "debug" -> {
                    if(args.length==2 && Set.of("perf","tasks","worlds").contains(args[1])){if(args[1].equals("worlds"))runtime.diagnostics().debugWorlds(sender);else messages.send(sender,runtime.diagnostics().perf());break;}
                    if(args.length>=2 && args[1].equalsIgnoreCase("economy")){messages.send(sender,runtime.progression().economy().diagnostics());break;}
                    if(args.length>=3 && args[1].equalsIgnoreCase("item")){
                        if(!(sender instanceof org.bukkit.entity.Player player))throw new IllegalArgumentException(I18n.text(sender, "该指令只能由玩家执行。", "This command can only be run by a player."));
                        String raw=args[2];String key=raw.contains(":")?raw:"battleroyale:"+raw;
                        int amount=args.length>3?Math.max(1,Integer.parseInt(args[3])):1;
                        var stack=new com.npucraft.battleroyale.paper.NativeLootItems().resolve(key);
                        stack.setAmount(Math.min(amount,stack.getMaxStackSize()));
                        var left=player.getInventory().addItem(stack);
                        left.values().forEach(rest->player.getWorld().dropItemNaturally(player.getLocation(),rest));
                        messages.send(sender, I18n.text(sender, "已发放：%s ×%d", "Given: %s x%d", key, amount));
                        break;
                    }
                    if(args.length==3 && args[1].equalsIgnoreCase("stats")){var target=org.bukkit.Bukkit.getPlayerExact(args[2]);if(target==null)throw new IllegalArgumentException(I18n.text(sender, "目标玩家必须在线。", "The target player must be online."));messages.send(sender,String.valueOf(runtime.progression().profile(target.getUniqueId())));break;}
                    if(args.length==2 && args[1].equalsIgnoreCase("storage")){messages.send(sender,runtime.storageDiagnostics());}
                    else if(args.length==2 && args[1].equalsIgnoreCase("recovery")){messages.send(sender,runtime.recoveryDiagnostics());}
                    else if (args.length == 2 && args[1].equalsIgnoreCase("rooms")) {
                        var definitions = foundation.state().rooms().all();
                        messages.count(sender, I18n.text(sender, "房间数", "Rooms"), definitions.size()); definitions.forEach(room -> messages.room(sender, room));
                    } else if (args.length == 2 && args[1].equalsIgnoreCase("maps")) {
                        var maps = foundation.state().maps().all();
                        messages.count(sender, I18n.text(sender, "地图模板数", "Map templates"), maps.size()); maps.forEach(map -> messages.map(sender, map));
                    } else if (args.length == 3) {
                        switch (args[1].toLowerCase(Locale.ROOT)) {
                            case "teams" -> {var session=rooms.session(args[2]).orElseThrow(()->new IllegalArgumentException(I18n.text(sender, "该房间没有正在进行的比赛。", "There is no active match in that room.")));session.teams().values().forEach(team->messages.team(sender,session,team));}
                            case "offline" -> {messages.send(sender,rooms.session(args[2]).map(s->runtime.matches().offline(s.sessionId())).orElse("offline=none"));}
                            case "deathboxes" -> {
                                var session=rooms.session(args[2]);
                                messages.send(sender,session.map(s->runtime.matches().deathboxes(s.sessionId())).orElse("deathboxes=0"));
                            }
                            case "loot" -> {
                                var session=rooms.session(args[2]);
                                messages.send(sender,session.map(s->runtime.matches().loot(s.sessionId())).orElse("loot=N/A"));
                            }
                            case "zone", "protection" -> {
                                if (rooms.rooms().stream().noneMatch(r -> r.id().equals(args[2]))) throw new IllegalArgumentException(I18n.text(sender, "房间不存在：%s", "Room not found: %s", args[2]));
                                var session = rooms.session(args[2]);
                                if (session.isPresent()) messages.zone(sender, session.orElseThrow(),runtime.matches().now());
                                else messages.send(sender, "room=" + args[2] + " session=N/A state=WAITING phase=N/A stage=N/A initial=N/A current=N/A next=N/A protection=N/A");
                            }
                            case "start" -> { rooms.debugStart(args[2]); messages.send(sender, I18n.text(sender, "已请求开始房间：%s", "Start requested for room: %s", args[2])); }
                            case "end" -> { rooms.debugEnd(args[2]); messages.send(sender, I18n.text(sender, "已请求结束房间：%s", "End requested for room: %s", args[2])); }
                            case "session" -> {
                                var session = rooms.session(args[2]);
                                if (session.isPresent()){messages.session(sender, session.orElseThrow(), rooms.remaining(session.orElseThrow()),runtime.matches().now());messages.send(sender,runtime.recoverySession(session.orElseThrow().sessionId()));}
                                else {
                                    var room = rooms.rooms().stream().filter(r -> r.id().equals(args[2])).findFirst()
                                            .orElseThrow(() -> new IllegalArgumentException(I18n.text(sender, "房间不存在：%s", "Room not found: %s", args[2])));
                                    messages.send(sender, "room=" + room.id() + " session=N/A state=WAITING players=[] min/max="
                                            + room.minPlayers() + "/" + room.maxPlayers() + " map=N/A world=N/A path=N/A countdown=N/A");
                                }
                            }
                            default -> messages.unknown(sender);
                        }
                    } else messages.unknown(sender);
                }
                default -> messages.unknown(sender);
            }
        } catch (IllegalArgumentException | IllegalStateException error) { messages.send(sender, error.getMessage()); }
        return true;
    }
    /** Quick join prioritises a queuable room; spectating a running match is only the fallback. */
    private void autojoinOrSpectate(Player player, RoomRuntimeService rooms){
        if(rooms.participant(player.getUniqueId()).isPresent()||rooms.joinableRoom().isPresent()){rooms.autojoin(player.getUniqueId());return;}
        runtime.spectators().external(player,rooms.spectatorMatch().orElseThrow(()->new IllegalStateException("No room available")));
    }
    private boolean adminAccess(CommandSender sender){return java.util.stream.Stream.of("battleroyale.admin","battleroyale.admin.map","battleroyale.admin.config","battleroyale.admin.cosmetic","battleroyale.admin.diagnostics").anyMatch(sender::hasPermission);}
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("battleroyale.command")) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.addAll(List.of("help", "version"));
            if (sender.hasPermission("battleroyale.play")) choices.addAll(List.of("rooms", "join", "autojoin", "leave", "team", "spectate", "lobby", "profile", "leaderboard", "shop", "cosmetics", "vote", "economy"));
            if (sender.hasPermission("battleroyale.admin")) choices.addAll(List.of("reload", "debug"));
            if(adminAccess(sender))choices.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("economy") && sender.hasPermission("battleroyale.admin"))
            choices.add("list");
        else if (args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("battleroyale.admin"))
            choices.addAll(List.of("perf", "tasks", "worlds", "economy", "stats", "storage", "recovery", "rooms", "maps", "session", "zone", "protection", "loot", "deathboxes", "teams", "offline", "start", "end"));
        else if(args[0].equalsIgnoreCase("admin") && adminAccess(sender)) {
            if(args.length==2){if(sender.hasPermission("battleroyale.admin"))choices.add("loadout");if(sender.hasPermission("battleroyale.admin.cosmetic"))choices.addAll(List.of("cosmetic","purchases"));if(sender.hasPermission("battleroyale.admin.map"))choices.add("map");if(sender.hasPermission("battleroyale.admin.config"))choices.add("config");if(sender.hasPermission("battleroyale.admin.diagnostics"))choices.addAll(List.of("diagnose","supportbundle"));}
            else if(args.length==3 && args[1].equalsIgnoreCase("map"))choices.addAll(List.of("list","info","edit","editor","validate","pregenerate","save","discard","exit","area","ground","spectator","remove","name"));
            else if(args.length==4 && args[1].equalsIgnoreCase("map") && Set.of("info","edit","validate","pregenerate").contains(args[2])){foundation.state().maps().all().forEach(m->choices.add(m.id()));if(args[2].equals("pregenerate"))choices.addAll(List.of("cancel","commit"));}
            else if(args.length==5 && args[1].equalsIgnoreCase("map") && args[2].equals("validate"))choices.add("--deep");
            else if(args.length==5 && args[1].equalsIgnoreCase("map") && args[2].equals("pregenerate"))foundation.state().maps().all().forEach(m->choices.add(m.id()));
            else if(args.length==6 && args[1].equalsIgnoreCase("map") && args[3].equals("commit"))choices.add("confirm");
            else if(args.length==3 && args[1].equalsIgnoreCase("config"))choices.addAll(List.of("validate","backup"));
            else if(args.length==3 && args[1].equalsIgnoreCase("cosmetic"))choices.addAll(List.of("grant","revoke"));
            else if(args.length==4 && args[1].equalsIgnoreCase("cosmetic"))org.bukkit.Bukkit.getOnlinePlayers().forEach(p->choices.add(p.getName()));
            else if(args.length==5 && args[1].equalsIgnoreCase("cosmetic"))choices.addAll(runtime.progression().config().cosmetics().keySet());
            else if(args.length==3 && args[1].equalsIgnoreCase("loadout")) choices.add("edit");
            else if(args.length==4 && args[1].equalsIgnoreCase("loadout") && args[2].equalsIgnoreCase("edit")) runtime.rooms().rooms().forEach(room->choices.add(room.id()));
        }
        else if(args.length==2 && args[0].equalsIgnoreCase("leaderboard") && sender.hasPermission("battleroyale.play"))choices.addAll(List.of("rating","kill_score","wins","kills","assists","damage"));
        else if (args.length == 2 && Set.of("join","spectate").contains(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("battleroyale.play")
                || args.length == 3 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("battleroyale.admin")
                && Set.of("session", "zone", "protection", "loot", "deathboxes", "teams", "offline", "start", "end").contains(args[1].toLowerCase(Locale.ROOT)))
            runtime.rooms().rooms().forEach(room -> choices.add(room.id()));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
