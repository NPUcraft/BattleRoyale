package com.npucraft.battleroyale.map;

import java.nio.file.Path;
import java.util.Set;

/** Paper 26.2 places every world in one level's dimensions tree. Only our two namespaces are owned. */
public record RuntimeLayout(Path levelDirectory, String namespace) {
    public static final String GAME = "battleroyale_game";
    public static final String MAINTENANCE = "battleroyale_maintenance";
    public static final String STORAGE_ID = "paper-dimension-v1";
    public RuntimeLayout {
        levelDirectory = levelDirectory.toAbsolutePath().normalize();
        if (!Set.of(GAME, MAINTENANCE).contains(namespace))
            throw new IllegalArgumentException("Not a BattleRoyale runtime namespace: " + namespace);
    }
    public Path runtimeRoot() { return levelDirectory.resolve("dimensions").resolve(namespace); }
    public String worldName(String leaf) { return namespace + "_" + leaf; }
    public String worldKey(String leaf) { return namespace + ":" + leaf; }
    public boolean permits(String type) {
        return namespace.equals(GAME) ? type.equals("GAME") : Set.of("EDITOR", "MAINTENANCE").contains(type);
    }
}
