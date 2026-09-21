package com.lastsector.config;
import java.nio.file.Path;
import java.util.Objects;
/** Settings only; storage and economy selection do not initialize integrations in M1. */
public record PluginSettings(boolean debug, String storageType, String economyProvider, Path runtimeDirectory, String lobbyWorld, ZoneUiSettings zoneUi, CombatSettings combat,DisconnectSettings disconnect) {
    public PluginSettings(boolean debug,String storageType,String economyProvider,Path runtimeDirectory,String lobbyWorld,ZoneUiSettings zoneUi,CombatSettings combat) {
        this(debug,storageType,economyProvider,runtimeDirectory,lobbyWorld,zoneUi,combat,DisconnectSettings.DEFAULT);
    }
    public PluginSettings(boolean debug,String storageType,String economyProvider,Path runtimeDirectory,String lobbyWorld,ZoneUiSettings zoneUi) {
        this(debug,storageType,economyProvider,runtimeDirectory,lobbyWorld,zoneUi,CombatSettings.DEFAULT);
    }
    public PluginSettings(boolean debug, String storageType, String economyProvider, Path runtimeDirectory, String lobbyWorld) {
        this(debug, storageType, economyProvider, runtimeDirectory, lobbyWorld, ZoneUiSettings.DEFAULT);
    }
    public PluginSettings(boolean debug, String storageType, String economyProvider, Path runtimeDirectory) {
        this(debug, storageType, economyProvider, runtimeDirectory, "world");
    }
    public PluginSettings {
        Objects.requireNonNull(zoneUi);
        Objects.requireNonNull(combat);
        Objects.requireNonNull(disconnect);
        Objects.requireNonNull(runtimeDirectory);
        com.lastsector.util.Checks.text(lobbyWorld, "lobbyWorld");
        if (!java.util.Set.of("sqlite", "mysql").contains(storageType)) throw new IllegalArgumentException("storage must be sqlite or mysql");
        if (!java.util.Set.of("auto", "coinsengine", "vault", "none").contains(economyProvider))
            throw new IllegalArgumentException("economy provider must be auto, coinsengine, vault or none");
    }
}
