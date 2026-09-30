package com.npucraft.battleroyale.map;
/** Server-thread world API boundary. No filesystem copying or deletion here. */
public interface WorldGateway {
    /** Rejects a template currently loaded by the server; default allows pure test adapters. */
    default void validateTemplate(MapTemplate template) {}
    void load(GameWorld world);
    /** Confirms no world remains loaded at this path; refuses if occupants or unload failure remain. */
    void unload(GameWorld world);
}

