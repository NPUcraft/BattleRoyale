package com.npucraft.battleroyale.service;
import com.npucraft.battleroyale.config.ConfigurationSnapshot;
import com.npucraft.battleroyale.map.MapTemplate;
import com.npucraft.battleroyale.room.RoomDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import java.util.logging.Logger;

/** Centralized console and player messages. Configuration values are sent as literal text. */
public final class MessageService {
    private final Logger logger;
    private final java.util.Map<java.util.UUID,String[]> offlineReturns=new java.util.HashMap<>();
    public void offlineResult(java.util.UUID player,boolean timeout){offlineReturns.put(player,timeout?new String[]{"重连时间已过，你已被淘汰，原始大厅状态已恢复。","Your reconnect window expired. You were eliminated and your lobby state was restored."}:new String[]{"你在离线期间被淘汰，原始大厅状态已恢复。","You were eliminated while offline. Your lobby state was restored."});}
    public void offlineWinner(java.util.UUID player,boolean tie){offlineReturns.put(player,tie?new String[]{"比赛以平局结束，原始大厅状态已恢复。","The match ended in a draw. Your lobby state was restored."}:new String[]{"你的队伍获胜，原始大厅状态已恢复。","Your team won. Your lobby state was restored."});}
    public void deliverOfflineResult(org.bukkit.entity.Player player){String[] text=offlineReturns.remove(player.getUniqueId());if(text!=null)send(player,text[0],text[1]);}
    public Component reconnectFailure(){return reconnectFailure(null);}
    public Component reconnectFailure(CommandSender player){return UiText.error(player,"重连状态暂时无法安全恢复，请重新连接。","Your match state could not be restored safely. Please reconnect.");}
    public MessageService(Logger logger) { this.logger = logger; }
    public void send(CommandSender sender, String message) {
        sender.sendMessage(UiText.message(I18n.error(sender,message)));
    }
    public void send(CommandSender sender,String zh,String en,Object... args){sender.sendMessage(UiText.message(sender,zh,en,args));}
    public void help(CommandSender sender) {
        send(sender, "帮助：/br help　版本：/br version", "Help: /br help | Version: /br version");
        if(sender.hasPermission("battleroyale.play")) {
            send(sender,"大厅：/br lobby　个人档案：/br profile　排行榜：/br leaderboard [rating|kill_score|wins|kills|assists|damage]","Lobby: /br lobby | Profile: /br profile | Leaderboard: /br leaderboard [rating|kill_score|wins|kills|assists|damage]");
            send(sender,"商店：/br shop　已拥有外观：/br cosmetics　房间列表：/br rooms","Shop: /br shop | Owned cosmetics: /br cosmetics | Rooms: /br rooms");
            send(sender,"加入：/br join <房间ID>　自动加入：/br autojoin　离开：/br leave　队伍：/br team　观战：/br spectate <房间ID>","Join: /br join <room-id> | Auto-join: /br autojoin | Leave: /br leave | Team: /br team | Spectate: /br spectate <room-id>");
        }
        if(sender.hasPermission("battleroyale.admin")) {
            send(sender,"重载：/br reload　装备编辑：/br admin loadout edit <房间ID>　开始/结束比赛：/br debug start|end <房间ID>","Reload: /br reload | Loadout editor: /br admin loadout edit <room-id> | Start/end: /br debug start|end <room-id>");
            send(sender,"运行诊断：/br debug storage|recovery|rooms|maps|perf|worlds|tasks　比赛诊断：/br debug session|zone|protection|loot|deathboxes|teams|offline <房间ID>","Runtime diagnostics: /br debug storage|recovery|rooms|maps|perf|worlds|tasks | Match diagnostics: /br debug session|zone|protection|loot|deathboxes|teams|offline <room-id>");
        }
        if(sender.hasPermission("battleroyale.admin.cosmetic")) send(sender,"外观授权：/br admin cosmetic grant|revoke <玩家> <外观ID>　购买复核：/br admin purchases","Cosmetics: /br admin cosmetic grant|revoke <player> <cosmetic-id> | Purchase review: /br admin purchases");
        if(sender.hasPermission("battleroyale.admin.map")) send(sender,"地图管理：/br admin map list|info|edit|validate|pregenerate <地图ID>　编辑菜单：/br admin map editor","Maps: /br admin map list|info|edit|validate|pregenerate <map-id> | Editor: /br admin map editor");
        if(sender.hasPermission("battleroyale.admin.config")) send(sender,"配置校验/备份：/br admin config validate|backup","Validate/back up config: /br admin config validate|backup");
        if(sender.hasPermission("battleroyale.admin.diagnostics")) send(sender,"健康检查：/br admin diagnose　诊断包：/br admin supportbundle","Health check: /br admin diagnose | Support bundle: /br admin supportbundle");
    }
    public void denied(CommandSender sender) { send(sender, "你没有使用此命令的权限。", "You do not have permission to use this command."); }
    public void version(CommandSender sender, String version) { send(sender, "版本 %s | Paper：26.2 | Java：25 | 数据库版本：V2 | %s", "Version %s | Paper: 26.2 | Java: 25 | Database: V2 | %s",version,com.npucraft.battleroyale.admin.BuildInfo.text()); }
    public void reloaded(CommandSender sender) { send(sender, "配置已成功重载。", "Configuration reloaded successfully."); }
    public void reloadFailed(CommandSender sender, String reason) { send(sender, "重载失败，已保留原配置。%s", "Reload failed. The previous configuration was retained. %s", I18n.error(sender,reason)); }
    public void unknown(CommandSender sender) { send(sender, "未知命令，请使用 /battleroyale help 查看帮助。", "Unknown command. Use /battleroyale help."); }
    public void count(CommandSender sender, String type, int count) { send(sender, type + ": " + count); }
    public void room(CommandSender sender, RoomDefinition room) {
        send(sender, room.id() + " (" + com.npucraft.battleroyale.paper.LobbyText.defaultLabel(sender,room.displayName()) + ") players=" + room.minPlayers() + ".." + room.maxPlayers()
                + ", teamSize=" + room.teamSize() + ", maps=" + room.mapPool() + ", zone=" + room.zoneProfileId());
    }
    public void map(CommandSender sender, MapTemplate map) {
        send(sender, map.id() + " (" + map.displayName() + ") directory=" + map.templatePath() + ", area=" + map.playableArea());
    }
    public void loaded(ConfigurationSnapshot snapshot) {
        // JavaPlugin's logger already supplies [BattleRoyale].
        logger.info("Loaded " + snapshot.rooms().size() + " rooms.");
        logger.info("Loaded " + snapshot.maps().size() + " map templates.");
        logger.info("Milestone 9 initialized. Recovery bootstrap started; joins remain gated.");
        if (snapshot.settings().debug()) logger.info("Debug enabled. Legacy runtime setting (retained, not migrated): " + snapshot.settings().runtimeDirectory());
    }
    public void startupFailed(Exception error) { logger.log(java.util.logging.Level.SEVERE, "Startup failed; disabling BattleRoyale. " + error.getMessage(), error); }
    public void reloadError(Exception error) { logger.warning("Configuration reload rejected: " + error.getMessage()); }
    public void stopped() { logger.info("Foundation stopped."); }
    public void runtimeError(String context, Throwable error) { logger.log(java.util.logging.Level.SEVERE, context + ": " + error.getMessage(), error); }
    public void event(CommandSender sender, String event, Object... args) {
        String pattern = switch (event) {
            case "start-blocked" -> "%s";
            case "teams-assigned" -> "已均衡分配为 %s 支队伍。";
            case "disconnected" -> "%s 已断线，可在 %s 秒内重连。";
            case "reconnected" -> "重连成功，当前比赛状态已恢复。";
            case "offline-eliminated" -> "你在离线期间被淘汰，正在返回大厅。";
            case "offline-timeout" -> "%s 重连超时，已被淘汰。";
            case "spectator-joined" -> "正在观战房间 %s，使用 /br leave 返回大厅。";
            case "spectator-left" -> "已退出观战，大厅状态已恢复。";
            case "joined" -> "已加入房间 %s。";
            case "left" -> "已离开房间 %s。";
            case "countdown-started" -> "比赛倒计时开始：%s 秒。";
            case "countdown-cancelled" -> "人数不足，倒计时已取消并重置。";
            case "countdown-remaining" -> "比赛将在 %s 秒后开始。";
            case "preparing" -> "正在准备比赛，已选择地图 %s，正在加载比赛世界……";
            case "preparation-failed" -> "世界准备失败，比赛已取消：%s";
            case "started" -> "比赛已在 %s 开始，所有玩家已安全落地。";
            case "protection-started" -> "落地保护已开启，持续 %s 秒。";
            case "protection-ended" -> "落地保护已结束，现在可以攻击其他玩家。";
            case "ended" -> "比赛已结束，正在返回大厅并清理。";
            case "returned" -> "已返回大厅。";
            default -> throw new IllegalArgumentException("Unknown runtime message: " + event);
        };
        Object[] displayed=args.clone();
        if((event.equals("start-blocked") || event.equals("preparation-failed")) && displayed.length>0 && displayed[0] instanceof String text)
            displayed[0]=I18n.error(sender,text);
        String english=switch(event){
            case "start-blocked" -> "%s";case "teams-assigned" -> "Players were balanced into %s teams.";
            case "disconnected" -> "%s disconnected and can rejoin within %s seconds.";
            case "reconnected" -> "Reconnected. Your match state was restored.";
            case "offline-eliminated" -> "You were eliminated while offline. Returning to the lobby.";
            case "offline-timeout" -> "%s did not reconnect in time and was eliminated.";
            case "spectator-joined" -> "Spectating room %s. Use /br leave to return to the lobby.";
            case "spectator-left" -> "Stopped spectating. Your lobby state was restored.";
            case "joined" -> "Joined room %s.";case "left" -> "Left room %s.";
            case "countdown-started" -> "Match countdown started: %s seconds.";
            case "countdown-cancelled" -> "Not enough players. The countdown was cancelled and reset.";
            case "countdown-remaining" -> "The match starts in %s seconds.";
            case "preparing" -> "Preparing the match on map %s. Loading the match world...";
            case "preparation-failed" -> "World preparation failed. The match was cancelled: %s";
            case "started" -> "The match started in %s. All players landed safely.";
            case "protection-started" -> "Landing protection is active for %s seconds.";
            case "protection-ended" -> "Landing protection ended. You can now attack other players.";
            case "ended" -> "The match ended. Returning to the lobby and cleaning up.";
            case "returned" -> "Returned to the lobby.";
            default -> throw new IllegalArgumentException("Unknown runtime message: "+event);
        };
        send(sender,pattern,english,displayed);
    }
    public void runtimeRoom(CommandSender sender, com.npucraft.battleroyale.room.RoomDefinition room, com.npucraft.battleroyale.session.GameSession session) {
        send(sender,"%s（%s） 状态：%s　人数：%s/%s","%s (%s) Status: %s | Players: %s/%s",com.npucraft.battleroyale.paper.LobbyText.defaultLabel(sender,room.displayName()),room.id(),I18n.state(sender,session==null?"WAITING":session.state()),session==null?0:session.players().size(),room.maxPlayers());
    }
    public void session(CommandSender sender, com.npucraft.battleroyale.session.GameSession session, int remaining,long now) {
        send(sender, "room=" + session.room().id() + " session=" + session.sessionId() + " state=" + session.state()
                + " players=" + session.players().keySet() + " min/max=" + session.room().minPlayers() + "/" + session.room().maxPlayers()
                + " mapRevision="+session.selectedMap().map(MapTemplate::metadataRevision).orElse(0L)+ " map=" + session.selectedMap().map(MapTemplate::id).orElse("N/A")
                + " world=" + session.gameWorld().map(com.npucraft.battleroyale.map.GameWorld::worldName).orElse("N/A")
                + " path=" + session.gameWorld().map(world -> world.runtimePath().toString()).orElse("N/A")
                + " countdown=" + (remaining < 0 ? "N/A" : remaining)
                + " stats=" + session.players().values() + " outcome=" + session.outcome().map(Object::toString).orElse("N/A"));
        zone(sender, session,now);
    }
    public void team(CommandSender sender,com.npucraft.battleroyale.session.GameSession session,com.npucraft.battleroyale.team.GameTeam team) {
        send(sender,"队伍 %s id=%s 存活人数=%s 成员=%s","Team %s id=%s Alive=%s Members=%s",team.displayIndex(),team.teamId(),team.playerIds().stream().filter(session::combatActive).count(),
                team.playerIds().stream().sorted().map(id->{var player=org.bukkit.Bukkit.getOfflinePlayer(id);return (player.getName()==null?id.toString():player.getName())+": "+I18n.state(sender,session.players().get(id).state());}).toList());
    }
    public void zone(CommandSender sender, com.npucraft.battleroyale.session.GameSession session,long now) {
        var zone = session.zone().orElse(null);
        send(sender, "room=" + session.room().id() + " session=" + session.sessionId() + " state=" + session.state()
                + " phase=" + (zone == null ? "N/A" : zone.phase()) + " stage=" + (zone == null ? "N/A" : zone.stageIndex()+1)
                + " initial=" + session.initialZone().map(Object::toString).orElse("N/A")
                + " current=" + (zone == null ? "N/A" : zone.current()) + " next=" + (zone == null || zone.next()==null ? "N/A" : zone.next())
                + " remainingSeconds=" + (zone == null ? "N/A" : zone.remainingSeconds())
                + " protectionSeconds=" + session.protection().map(p -> String.valueOf(p.remaining(now))).orElse("N/A"));
    }
}
