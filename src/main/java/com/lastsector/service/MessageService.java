package com.lastsector.service;
import com.lastsector.config.ConfigurationSnapshot;
import com.lastsector.map.MapTemplate;
import com.lastsector.room.RoomDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import java.util.logging.Logger;

/** Centralized console and player messages. Configuration values are sent as literal text. */
public final class MessageService {
    private final Logger logger;
    public MessageService(Logger logger) { this.logger = logger; }
    public void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[LastSector] " + message));
    }
    public void help(CommandSender sender) {
        send(sender, "/lastsector help | version");
        if (sender.hasPermission("lastsector.play")) send(sender, "/lastsector rooms | join <room> | autojoin | leave");
        if (sender.hasPermission("lastsector.admin")) send(sender, "/lastsector reload | admin loadout edit <room> | debug rooms/maps | debug session/zone/protection/loot/deathboxes/start/end <room>");
    }
    public void denied(CommandSender sender) { send(sender, "You do not have permission to use this command."); }
    public void version(CommandSender sender, String version) { send(sender, "Version " + version + " (Milestone 5 Combat, DeathBoxes & Outcomes)"); }
    public void reloaded(CommandSender sender) { send(sender, "Configuration reloaded successfully."); }
    public void reloadFailed(CommandSender sender, String reason) { send(sender, "Reload failed; previous configuration retained. " + reason); }
    public void unknown(CommandSender sender) { send(sender, "Unknown command. Use /lastsector help."); }
    public void count(CommandSender sender, String type, int count) { send(sender, type + ": " + count); }
    public void room(CommandSender sender, RoomDefinition room) {
        send(sender, room.id() + " (" + room.displayName() + ") players=" + room.minPlayers() + ".." + room.maxPlayers()
                + ", teamSize=" + room.teamSize() + ", maps=" + room.mapPool() + ", zone=" + room.zoneProfileId());
    }
    public void map(CommandSender sender, MapTemplate map) {
        send(sender, map.id() + " (" + map.displayName() + ") directory=" + map.templatePath() + ", area=" + map.playableArea());
    }
    public void loaded(ConfigurationSnapshot snapshot) {
        // JavaPlugin's logger already supplies [LastSector].
        logger.info("Loaded " + snapshot.rooms().size() + " rooms.");
        logger.info("Loaded " + snapshot.maps().size() + " map templates.");
        logger.info("Milestone 5 initialized. Combat, elimination, DeathBoxes and Solo outcomes ready.");
        if (snapshot.settings().debug()) logger.info("Debug enabled. Runtime directory: " + snapshot.settings().runtimeDirectory());
    }
    public void startupFailed(Exception error) { logger.log(java.util.logging.Level.SEVERE, "Startup failed; disabling LastSector. " + error.getMessage(), error); }
    public void reloadError(Exception error) { logger.warning("Configuration reload rejected: " + error.getMessage()); }
    public void stopped() { logger.info("Foundation stopped."); }
    public void runtimeError(String context, Throwable error) { logger.log(java.util.logging.Level.SEVERE, context + ": " + error.getMessage(), error); }
    public void event(CommandSender sender, String event, Object... args) {
        String pattern = switch (event) {
            case "joined" -> "Joined room %s.";
            case "left" -> "Left room %s.";
            case "countdown-started" -> "Countdown started: %s seconds.";
            case "countdown-cancelled" -> "Countdown cancelled: not enough players. Timer reset.";
            case "countdown-remaining" -> "Starting in %s seconds.";
            case "preparing" -> "Game preparing. Map selected: %s. Preparing runtime world...";
            case "preparation-failed" -> "World preparation failed; match cancelled: %s";
            case "started" -> "Game started in %s. Safe spawn complete.";
            case "protection-started" -> "PvP protection: %s seconds from landing.";
            case "protection-ended" -> "PvP protection expired. Player combat is now enabled.";
            case "ended" -> "Game ended. Returning to lobby and cleaning up.";
            case "returned" -> "Returned to lobby.";
            default -> throw new IllegalArgumentException("Unknown runtime message: " + event);
        };
        send(sender, pattern.formatted(args));
    }
    public void runtimeRoom(CommandSender sender, com.lastsector.room.RoomDefinition room, com.lastsector.session.GameSession session) {
        send(sender, room.id() + " state=" + (session == null ? "WAITING" : session.state())
                + " players=" + (session == null ? 0 : session.players().size()) + "/" + room.maxPlayers());
    }
    public void session(CommandSender sender, com.lastsector.session.GameSession session, int remaining) {
        send(sender, "room=" + session.room().id() + " session=" + session.sessionId() + " state=" + session.state()
                + " players=" + session.players().keySet() + " min/max=" + session.room().minPlayers() + "/" + session.room().maxPlayers()
                + " map=" + session.selectedMap().map(MapTemplate::id).orElse("N/A")
                + " world=" + session.gameWorld().map(com.lastsector.map.GameWorld::worldName).orElse("N/A")
                + " path=" + session.gameWorld().map(world -> world.runtimePath().toString()).orElse("N/A")
                + " countdown=" + (remaining < 0 ? "N/A" : remaining)
                + " stats=" + session.players().values() + " outcome=" + session.outcome().map(Object::toString).orElse("N/A"));
        zone(sender, session);
    }
    public void zone(CommandSender sender, com.lastsector.session.GameSession session) {
        var zone = session.zone().orElse(null);
        send(sender, "room=" + session.room().id() + " session=" + session.sessionId() + " state=" + session.state()
                + " phase=" + (zone == null ? "N/A" : zone.phase()) + " stage=" + (zone == null ? "N/A" : zone.stageIndex()+1)
                + " initial=" + session.initialZone().map(Object::toString).orElse("N/A")
                + " current=" + (zone == null ? "N/A" : zone.current()) + " next=" + (zone == null || zone.next()==null ? "N/A" : zone.next())
                + " remainingSeconds=" + (zone == null ? "N/A" : zone.remainingSeconds())
                + " protectionSeconds=" + session.protection().map(p -> String.valueOf(p.remaining(System.nanoTime()))).orElse("N/A"));
    }
}
