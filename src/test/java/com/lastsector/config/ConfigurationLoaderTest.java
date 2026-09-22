package com.lastsector.config;

import com.lastsector.service.FoundationService;
import com.lastsector.session.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationLoaderTest {
    @TempDir Path directory;
    @BeforeEach void copyDefaults() throws Exception {
        for (String file : new String[]{"config.yml", "rooms.yml", "maps.yml", "zones.yml"})
            try (var stream = getClass().getResourceAsStream("/" + file)) { Files.copy(stream, directory.resolve(file)); }
    }
    private ConfigurationSnapshot load() { return new ConfigurationLoader(directory).load(); }
    private void replace(String file, String from, String to) throws Exception {
        Path path = directory.resolve(file);
        Files.writeString(path, Files.readString(path).replace(from, to));
    }
    @Test void recoveryDefaultsAndCredentialsAreNotRendered(){var settings=load().settings();assertEquals(5,settings.recovery().checkpoint().toSeconds());assertEquals(60,settings.recovery().orphanAge().toMinutes());assertEquals(directory.resolve("data/lastsector.db"),settings.database().sqliteFile());assertTrue(settings.database().toString().contains("redacted"));}
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "checkpoint-seconds: 5|checkpoint-seconds: 0",
        "orphan-delete-after-minutes: 60|orphan-delete-after-minutes: 0",
        "checkpoint-seconds: 5|checkpoint-seconds: 301",
        "file: data/lastsector.db|file: ../outside.db",
        "port: 3306|port: 65536",
        "127.0.0.1|localhost/?password=secret",
        "database: lastsector|database: db?param=secret",
        "connection-timeout-ms: 5000|connection-timeout-ms: 60001"
    }) void rejectsUnsafeRecoverySettings(String from,String to)throws Exception{replace("config.yml",from,to);assertThrows(ConfigurationException.class,this::load);}
    @Test void loadsEveryDefault() {
        var result = load();
        assertEquals(2, result.rooms().size()); assertEquals(2, result.maps().size());
        assertEquals(1, result.zoneProfiles().size()); assertEquals(4, result.zoneProfiles().getFirst().stages().size());
        assertEquals(directory.resolve("runtime"), result.settings().runtimeDirectory());
        assertEquals(4, result.rooms().get(1).teamSize());
        assertFalse(Files.exists(directory.resolve("runtime")));
        assertFalse(Files.exists(directory.resolve("maps")));
        assertEquals("world", result.settings().lobbyWorld());
        assertEquals(30, result.rooms().getFirst().countdownDuration().getSeconds());
        assertEquals(CombatSettings.DEFAULT,result.settings().combat());
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "config.yml|reconnect-seconds: 120|reconnect-seconds: -1|disconnect.reconnect-seconds",
        "config.yml|radius: 24|radius: -1|disconnect.mob-aggro.radius",
        "config.yml|interval-ticks: 20|interval-ticks: 0|disconnect.mob-aggro.interval-ticks",
        "config.yml|interval-ticks: 20|interval-ticks: 1201|disconnect",
        "config.yml|reconnect-seconds: 120|reconnect-seconds: 3601|disconnect",
        "config.yml|radius: 24|radius: 65|disconnect",
        "config.yml|attribution-seconds: 15|attribution-seconds: 0|combat.attribution-seconds",
        "config.yml|min-damage: 4.0|min-damage: -1|combat.assist.min-damage",
        "config.yml|min-damage-share: 0.20|min-damage-share: 1.1|combat/match/deathbox",
        "config.yml|winner-showcase-seconds: 60|winner-showcase-seconds: -1|match.winner-showcase-seconds",
        "config.yml|interaction-distance: 6.0|interaction-distance: .nan|deathbox.interaction-distance",
        "zones.yml|target-half-size: 400|target-half-size: 500|profiles.default.stages[0]",
        "zones.yml|target-half-size: 400|target-half-size: 700|profiles.default.stages[0]",
        "zones.yml|target-half-size: 50|target-half-size: 0|profiles.default.stages[3]",
        "zones.yml|wait-seconds: 300|wait-seconds: -1|profiles.default.stages[0]",
        "zones.yml|shrink-seconds: 120|shrink-seconds: 0|profiles.default.stages[0]",
        "zones.yml|half-size: 500|half-size: 499|initial-size-by-players[0]",
        "zones.yml|max-players: 16|max-players: 8|initial-size-by-players[1]",
        "zones.yml|max-players: 32|max-players: 31|rooms.squad.max-players",
        "zones.yml|extra-damage-per-block: 0.01|extra-damage-per-block: -.1|stages[0]",
        "zones.yml|base-damage-per-second: 1.0|base-damage-per-second: .NaN|stages[0]",
        "zones.yml|max-damage-per-second: 6.0|max-damage-per-second: 0|stages[0]",
        "rooms.yml|min-distance: 64|min-distance: -1|rooms.solo.spawn",
        "rooms.yml|max-attempts-per-player: 200|max-attempts-per-player: 0|rooms.solo.spawn",
        "config.yml|particle: END_ROD|particle: UNKNOWN|zone-ui.particle-wall.particle",
        "config.yml|particle: END_ROD|particle: BLOCK|zone-ui.particle-wall.particle",
        "config.yml|spacing: 2.5|spacing: 0|zone-ui",
        "config.yml|max-particles-per-player: 300|max-particles-per-player: 1001|zone-ui",
        "config.yml|view-distance: 64|view-distance: .inf|zone-ui"
    })
    void m3ValidationHasContext(String file,String from,String to,String context) throws Exception {
        replace(file,from,to);
        assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains(context));
    }
    @Test void crossValidationNamesRoomMapProfileAndRequiredArea() throws Exception {
        replace("maps.yml","min-x: -3000","min-x: 1500");
        var error=assertThrows(ConfigurationException.class,this::load).getMessage();
        for(String value:new String[]{"room=solo","map=city","profile=default","2000","1500"}) assertTrue(error.contains(value),error);
    }
    @Test void unreachableLargerBucketsDoNotRejectSmallRoomMaps() throws Exception {
        replace("rooms.yml","max-players: 24","max-players: 8"); replace("rooms.yml","max-players: 32","max-players: 8");
        replace("maps.yml","min-x: -3000","min-x: 2000");
        assertEquals(2,load().rooms().size());
    }
    @Test void firstTargetMustFitEveryBucketEvenIfHalfSizesNotMonotonic() throws Exception {
        replace("zones.yml","half-size: 500","half-size: 900");
        replace("zones.yml","half-size: 750","half-size: 500");
        replace("zones.yml","target-half-size: 400","target-half-size: 600");
        assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains("stages[0]"));
    }
    @Test void emptyStagesRejectedBeforePublishing() throws Exception {
        Path path=directory.resolve("zones.yml");
        String yaml=Files.readString(path);
        Files.writeString(path,yaml.substring(0,yaml.indexOf("    stages:"))+"    stages: []\n");
        assertTrue(assertThrows(ConfigurationException.class,this::load).getMessage().contains("profiles.default.stages"));
    }
    @Test void missingM2FieldsRetainM1Compatibility() throws Exception {
        replace("rooms.yml", "    countdown-seconds: 30", "");
        replace("config.yml", "lobby:\n  world: world\n  use-world-spawn: true", "");
        var result = load();
        assertEquals(30, result.rooms().getFirst().countdownDuration().getSeconds());
        assertEquals("world", result.settings().lobbyWorld());
    }
    @Test void rejectsZeroCountdown() throws Exception {
        replace("rooms.yml", "countdown-seconds: 30", "countdown-seconds: 0");
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("rooms.solo.countdown-seconds"));
    }
    @Test void rejectsUnsupportedLobbyMode() throws Exception {
        replace("config.yml", "use-world-spawn: true", "use-world-spawn: false");
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("lobby.use-world-spawn"));
    }
    @Test void adapterValidationFailurePreservesOldSnapshot() {
        var service = new FoundationService(this::load, new SessionManager()); service.reload();
        var old = service.state();
        assertThrows(IllegalStateException.class, () -> service.reload(candidate -> { throw new IllegalStateException("Lobby missing"); }));
        assertSame(old, service.state());
    }
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "rooms.yml|min-players: 2|min-players: 0|rooms.solo.min-players",
        "rooms.yml|max-players: 24|max-players: 1|rooms.solo.max-players",
        "rooms.yml|team-size: 1|team-size: 0|rooms.solo.team-size",
        "rooms.yml|maps: [city, desert]|maps: []|rooms.solo.maps",
        "rooms.yml|maps: [city, desert]|maps: [unknown]|rooms.solo.maps",
        "rooms.yml|zone-profile: default|zone-profile: unknown|rooms.solo.zone-profile",
        "rooms.yml|min-players: 2|min-players: 2.5|rooms.solo.min-players",
        "rooms.yml|allow-external-spectators: true|allow-external-spectators: yesplease|rooms.solo.allow-external-spectators",
        "rooms.yml|pvp-protection-seconds: 60|pvp-protection-seconds: -1|rooms.solo.pvp-protection-seconds",
        "maps.yml|max-x: 3000|max-x: -4000|maps.city.playable-area.max-x",
        "maps.yml|maps/city|../outside|maps.city.directory",
        "config.yml|type: sqlite|type: invalid|storage.type",
        "config.yml|provider: auto|provider: missing|economy.provider",
        "config.yml|directory: runtime|directory: .|runtime-worlds.directory",
        "zones.yml|half-size: 500|half-size: 499|profiles.default.initial-size-by-players[0].half-size",
        "zones.yml|max-players: 16|max-players: 8|profiles.default.initial-size-by-players[1].max-players",
        "zones.yml|shrink-seconds: 120|shrink-seconds: 0|profiles.default.stages[0].shrink-seconds",
        "zones.yml|target-half-size: 250|target-half-size: 800|profiles.default.stages[1].target-half-size",
        "zones.yml|max-damage-per-second: 6.0|max-damage-per-second: 0.5|profiles.default.stages[0].max-damage-per-second",
        "zones.yml|extra-damage-per-block: 0.01|extra-damage-per-block: .nan|profiles.default.stages[0].extra-damage-per-block",
        "rooms.yml|max-players: 24|max-players: 33|rooms.solo.max-players"
    })
    void rejectsInvalidValuesWithContext(String file, String from, String to, String path) throws Exception {
        replace(file, from, to);
        var exception = assertThrows(ConfigurationException.class, this::load);
        assertTrue(exception.getMessage().contains(file), exception.getMessage());
        assertTrue(exception.getMessage().contains(path), exception.getMessage());
        assertTrue(exception.getMessage().contains("value="));
    }
    @Test void rejectsMalformedYaml() throws Exception {
        Files.writeString(directory.resolve("rooms.yml"), "rooms: [unterminated");
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("rooms.yml"));
    }
    @Test void oldConfigurationWithoutM5SectionsUsesDefaults() throws Exception {
        Path config=directory.resolve("config.yml");String yaml=Files.readString(config);
        int start=yaml.indexOf("combat:"),end=yaml.indexOf("storage:",start);
        Files.writeString(config,yaml.substring(0,start)+yaml.substring(end));
        assertEquals(CombatSettings.DEFAULT,load().settings().combat());
    }
    @Test void rejectsMissingFileAndField() throws Exception {
        replace("config.yml", "debug: false", "");
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("debug"));
        Files.delete(directory.resolve("rooms.yml"));
        // Restore the first file so the missing rooms file is reached.
        replace("config.yml", "storage:", "debug: false\nstorage:");
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("rooms.yml"));
    }
    @Test void failedReloadRetainsEntirePreviousState() throws Exception {
        var service = new FoundationService(this::load, new SessionManager());
        service.reload(); var before = service.state();
        replace("maps.yml", "City", "Changed City");
        replace("rooms.yml", "max-players: 24", "max-players: 1");
        assertThrows(ConfigurationException.class, service::reload);
        assertSame(before, service.state());
        assertEquals("City", service.state().maps().find("city").orElseThrow().displayName());
        replace("rooms.yml", "max-players: 1", "max-players: 24");
        service.reload();
        assertNotSame(before, service.state());
        assertEquals("Changed City", service.state().maps().find("city").orElseThrow().displayName());
    }
    @Test void initialFailurePublishesNothing() throws Exception {
        replace("rooms.yml", "team-size: 1", "team-size: 0");
        var service = new FoundationService(this::load, new SessionManager());
        assertThrows(ConfigurationException.class, service::reload);
        assertThrows(IllegalStateException.class, service::state);
    }
    @Test void reloadBlockedWhenSessionExists() {
        var sessions = new SessionManager();
        var service = new FoundationService(this::load, sessions); service.reload();
        var before = service.state();
        sessions.register(GameSession.waiting(UUID.randomUUID(), before.configuration().rooms().getFirst(), Instant.now()));
        assertThrows(IllegalStateException.class, service::reload);
        assertSame(before, service.state());
    }
    @Test void oldConfigurationWithoutDisconnectUsesM6Defaults() throws Exception {
        var file=directory.resolve("config.yml");var yaml=Files.readString(file);int start=yaml.indexOf("disconnect:"),end=yaml.indexOf("combat:",start);
        Files.writeString(file,yaml.substring(0,start)+yaml.substring(end));assertEquals(DisconnectSettings.DEFAULT,load().settings().disconnect());
    }
    @Test void zeroReconnectAndDisabledZeroRadiusMobAssistAreAccepted() throws Exception {
        replace("config.yml","reconnect-seconds: 120","reconnect-seconds: 0");replace("config.yml","radius: 24","radius: 0");
        assertTrue(load().settings().disconnect().reconnectWindow().isZero());assertEquals(0,load().settings().disconnect().mobRadius());
    }
}

