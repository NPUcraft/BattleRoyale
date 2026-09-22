package com.lastsector.command;
import com.lastsector.service.*;
import com.lastsector.config.ConfigurationException;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Permission/routing adapter; lifecycle and filesystem work stay in services. */
public final class LastSectorCommand implements CommandExecutor, TabCompleter {
    private final FoundationService foundation;
    private final PluginRuntime runtime;
    private final MessageService messages;
    private final String version;
    public LastSectorCommand(FoundationService foundation, PluginRuntime runtime, MessageService messages, String version) {
        this.foundation = foundation; this.runtime = runtime; this.messages = messages; this.version = version;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lastsector.command")) { messages.denied(sender); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (Set.of("reload", "debug", "admin").contains(action) && !sender.hasPermission("lastsector.admin")
                || Set.of("rooms", "join", "autojoin", "leave", "team", "spectate", "lobby", "profile", "leaderboard", "shop", "cosmetics").contains(action) && !sender.hasPermission("lastsector.play")) {
            messages.denied(sender); return true;
        }
        try {
            var rooms = runtime.rooms();
            switch (action) {
                case "lobby", "profile", "leaderboard", "shop", "cosmetics" -> {
                    if(!(sender instanceof Player player))throw new IllegalArgumentException("This command requires a player");
                    runtime.lobby().command(player,action,args.length>1?args[1]:null);
                }
                case "admin" -> {
                    if(args.length>=2 && args[1].equalsIgnoreCase("purchases")) {
                        runtime.progression().purchases().whenComplete((rows,error)->{if(error!=null)messages.send(sender,"Purchase diagnostics unavailable");else if(rows.isEmpty())messages.send(sender,"No MANUAL_REVIEW purchases");else rows.forEach(row->messages.send(sender,row.toString()));});break;
                    }
                    if(args.length==5 && args[1].equalsIgnoreCase("cosmetic") && Set.of("grant","revoke").contains(args[2])) {
                        var target=org.bukkit.Bukkit.getOfflinePlayerIfCached(args[3]);if(target==null)throw new IllegalArgumentException("Unknown cached player; use a player who has joined this server");
                        runtime.progression().grant(target.getUniqueId(),args[4],args[2].equals("revoke")).whenComplete((value,error)->messages.send(sender,error==null?"Cosmetic updated":"Cosmetic update failed"));break;
                    }
                    if(args.length!=4 || !args[1].equalsIgnoreCase("loadout") || !args[2].equalsIgnoreCase("edit")) { messages.send(sender,"Usage: /ls admin loadout edit <room>"); break; }
                    if(!(sender instanceof Player player)) throw new IllegalArgumentException("This command requires a player");
                    if(rooms.rooms().stream().map(r->rooms.session(r.id())).flatMap(Optional::stream).anyMatch(s->s.players().containsKey(player.getUniqueId())))
                        throw new IllegalStateException("Leave your room before editing a loadout");
                    var room=rooms.rooms().stream().filter(r->r.id().equals(args[3])).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown room: " + args[3]));
                    runtime.loadouts().open(player,room,rooms.rooms());
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
                    if(!(sender instanceof Player player))throw new IllegalArgumentException("This command requires a player");
                    var session=rooms.participant(player.getUniqueId()).orElseThrow(()->new IllegalStateException("You are not in a room"));
                    var team=session.players().get(player.getUniqueId()).teamId().map(session.teams()::get).orElseThrow(()->new IllegalStateException("Teams are assigned when the roster freezes"));
                    messages.team(sender,session,team);
                }
                case "spectate" -> {
                    if(!runtime.recoveryReady())throw new IllegalStateException("LastSector is still recovering sessions.");
                    if(!(sender instanceof Player player))throw new IllegalArgumentException("This command requires a player");
                    if(args.length!=2)throw new IllegalArgumentException("Usage: /ls spectate <room>");
                    if(rooms.participant(player.getUniqueId()).isPresent())throw new IllegalStateException("Already participating in a room");
                    var session=rooms.session(args[1]).orElseThrow(()->new IllegalArgumentException("Room has no active match"));runtime.spectators().external(player,session);
                }
                case "join", "autojoin", "leave" -> {
                    if (!(sender instanceof Player player)) { messages.send(sender, "This command requires a player."); break; }
                    if (action.equals("join") && args.length == 2) rooms.join(player.getUniqueId(), args[1]);
                    else if (action.equals("autojoin") && args.length == 1) rooms.autojoin(player.getUniqueId());
                    else if (action.equals("leave") && args.length == 1) {if(!runtime.spectators().leave(player.getUniqueId(),false))rooms.leave(player.getUniqueId());}
                    else messages.unknown(sender);
                }
                case "debug" -> {
                    if(args.length>=2 && args[1].equalsIgnoreCase("economy")){messages.send(sender,runtime.progression().economy().diagnostics());break;}
                    if(args.length==3 && args[1].equalsIgnoreCase("stats")){var target=org.bukkit.Bukkit.getPlayerExact(args[2]);if(target==null)throw new IllegalArgumentException("Player must be online");messages.send(sender,String.valueOf(runtime.progression().profile(target.getUniqueId())));break;}
                    if(args.length==2 && args[1].equalsIgnoreCase("storage")){messages.send(sender,runtime.storageDiagnostics());}
                    else if(args.length==2 && args[1].equalsIgnoreCase("recovery")){messages.send(sender,runtime.recoveryDiagnostics());}
                    else if (args.length == 2 && args[1].equalsIgnoreCase("rooms")) {
                        var definitions = foundation.state().rooms().all();
                        messages.count(sender, "Rooms", definitions.size()); definitions.forEach(room -> messages.room(sender, room));
                    } else if (args.length == 2 && args[1].equalsIgnoreCase("maps")) {
                        var maps = foundation.state().maps().all();
                        messages.count(sender, "Map templates", maps.size()); maps.forEach(map -> messages.map(sender, map));
                    } else if (args.length == 3) {
                        switch (args[1].toLowerCase(Locale.ROOT)) {
                            case "teams" -> {var session=rooms.session(args[2]).orElseThrow(()->new IllegalArgumentException("No active session"));session.teams().values().forEach(team->messages.team(sender,session,team));}
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
                                if (rooms.rooms().stream().noneMatch(r -> r.id().equals(args[2]))) throw new IllegalArgumentException("Room does not exist: " + args[2]);
                                var session = rooms.session(args[2]);
                                if (session.isPresent()) messages.zone(sender, session.orElseThrow(),runtime.matches().now());
                                else messages.send(sender, "room=" + args[2] + " session=N/A state=WAITING phase=N/A stage=N/A initial=N/A current=N/A next=N/A protection=N/A");
                            }
                            case "start" -> { rooms.debugStart(args[2]); messages.send(sender, "Start requested for " + args[2]); }
                            case "end" -> { rooms.debugEnd(args[2]); messages.send(sender, "End requested for " + args[2]); }
                            case "session" -> {
                                var session = rooms.session(args[2]);
                                if (session.isPresent()){messages.session(sender, session.orElseThrow(), rooms.remaining(session.orElseThrow()),runtime.matches().now());messages.send(sender,runtime.recoverySession(session.orElseThrow().sessionId()));}
                                else {
                                    var room = rooms.rooms().stream().filter(r -> r.id().equals(args[2])).findFirst()
                                            .orElseThrow(() -> new IllegalArgumentException("Room does not exist: " + args[2]));
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
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("lastsector.command")) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.addAll(List.of("help", "version"));
            if (sender.hasPermission("lastsector.play")) choices.addAll(List.of("rooms", "join", "autojoin", "leave", "team", "spectate", "lobby", "profile", "leaderboard", "shop", "cosmetics"));
            if (sender.hasPermission("lastsector.admin")) choices.addAll(List.of("reload", "debug", "admin"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("lastsector.admin"))
            choices.addAll(List.of("economy", "stats", "storage", "recovery", "rooms", "maps", "session", "zone", "protection", "loot", "deathboxes", "teams", "offline", "start", "end"));
        else if(args[0].equalsIgnoreCase("admin") && sender.hasPermission("lastsector.admin")) {
            if(args.length==2) choices.addAll(List.of("loadout","cosmetic","purchases"));
            else if(args.length==3 && args[1].equalsIgnoreCase("cosmetic"))choices.addAll(List.of("grant","revoke"));
            else if(args.length==4 && args[1].equalsIgnoreCase("cosmetic"))org.bukkit.Bukkit.getOnlinePlayers().forEach(p->choices.add(p.getName()));
            else if(args.length==5 && args[1].equalsIgnoreCase("cosmetic"))choices.addAll(runtime.progression().config().cosmetics().keySet());
            else if(args.length==3 && args[1].equalsIgnoreCase("loadout")) choices.add("edit");
            else if(args.length==4 && args[1].equalsIgnoreCase("loadout") && args[2].equalsIgnoreCase("edit")) runtime.rooms().rooms().forEach(room->choices.add(room.id()));
        }
        else if(args.length==2 && args[0].equalsIgnoreCase("leaderboard") && sender.hasPermission("lastsector.play"))choices.addAll(List.of("rating","kill_score","wins","kills","assists","damage"));
        else if (args.length == 2 && Set.of("join","spectate").contains(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("lastsector.play")
                || args.length == 3 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("lastsector.admin")
                && Set.of("session", "zone", "protection", "loot", "deathboxes", "teams", "offline", "start", "end").contains(args[1].toLowerCase(Locale.ROOT)))
            runtime.rooms().rooms().forEach(room -> choices.add(room.id()));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}

