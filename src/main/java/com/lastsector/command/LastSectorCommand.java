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
        if (Set.of("reload", "debug").contains(action) && !sender.hasPermission("lastsector.admin")
                || Set.of("rooms", "join", "autojoin", "leave").contains(action) && !sender.hasPermission("lastsector.play")) {
            messages.denied(sender); return true;
        }
        try {
            var rooms = runtime.rooms();
            switch (action) {
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
                case "join", "autojoin", "leave" -> {
                    if (!(sender instanceof Player player)) { messages.send(sender, "This command requires a player."); break; }
                    if (action.equals("join") && args.length == 2) rooms.join(player.getUniqueId(), args[1]);
                    else if (action.equals("autojoin") && args.length == 1) rooms.autojoin(player.getUniqueId());
                    else if (action.equals("leave") && args.length == 1) rooms.leave(player.getUniqueId());
                    else messages.unknown(sender);
                }
                case "debug" -> {
                    if (args.length == 2 && args[1].equalsIgnoreCase("rooms")) {
                        var definitions = foundation.state().rooms().all();
                        messages.count(sender, "Rooms", definitions.size()); definitions.forEach(room -> messages.room(sender, room));
                    } else if (args.length == 2 && args[1].equalsIgnoreCase("maps")) {
                        var maps = foundation.state().maps().all();
                        messages.count(sender, "Map templates", maps.size()); maps.forEach(map -> messages.map(sender, map));
                    } else if (args.length == 3) {
                        switch (args[1].toLowerCase(Locale.ROOT)) {
                            case "zone", "protection" -> {
                                if (rooms.rooms().stream().noneMatch(r -> r.id().equals(args[2]))) throw new IllegalArgumentException("Room does not exist: " + args[2]);
                                var session = rooms.session(args[2]);
                                if (session.isPresent()) messages.zone(sender, session.orElseThrow());
                                else messages.send(sender, "room=" + args[2] + " session=N/A state=WAITING phase=N/A stage=N/A initial=N/A current=N/A next=N/A protection=N/A");
                            }
                            case "start" -> { rooms.debugStart(args[2]); messages.send(sender, "Start requested for " + args[2]); }
                            case "end" -> { rooms.debugEnd(args[2]); messages.send(sender, "End requested for " + args[2]); }
                            case "session" -> {
                                var session = rooms.session(args[2]);
                                if (session.isPresent()) messages.session(sender, session.orElseThrow(), rooms.remaining(session.orElseThrow()));
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
            if (sender.hasPermission("lastsector.play")) choices.addAll(List.of("rooms", "join", "autojoin", "leave"));
            if (sender.hasPermission("lastsector.admin")) choices.addAll(List.of("reload", "debug"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("lastsector.admin"))
            choices.addAll(List.of("rooms", "maps", "session", "zone", "protection", "start", "end"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("join") && sender.hasPermission("lastsector.play")
                || args.length == 3 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("lastsector.admin")
                && Set.of("session", "zone", "protection", "start", "end").contains(args[1].toLowerCase(Locale.ROOT)))
            runtime.rooms().rooms().forEach(room -> choices.add(room.id()));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}

