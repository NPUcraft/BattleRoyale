package com.npucraft.battleroyale.loot;

import java.util.Set;

/** Live-match storage policy. Map-native containers are emptied at world load (WorldSanitizer),
 *  so container interaction is legal during a match: furnaces, brewing stands, barrels, chests
 *  and every other storage block may be opened AND placed freely; only the ender chest stays
 *  closed. Placement is denied only for the two blocks the pre-clear cannot make safe. */
public final class StorageGuardPolicy {
    private StorageGuardPolicy() {}
    /** Blocks whose placement is denied during a match:
     *  HOPPER silently siphons whatever sits above it (death boxes, airdrop crates, other
     *  players' chests); ENDER_CHEST is per-player global storage that would smuggle loot
     *  across matches (opening one is denied as well). */
    public static final Set<String> BLOCKED_PLACEMENT=Set.of("HOPPER","ENDER_CHEST");
    /** Entity types removed from the map because they carry equipment or an inventory. */
    public static final Set<String> REMOVED_ENTITIES=Set.of("ARMOR_STAND","ITEM_FRAME","GLOW_ITEM_FRAME","CHEST_MINECART","HOPPER_MINECART");
    /** Air-drop crates share this marker and remain the only openable barrel. */
    public static final String AIRDROP_KEY="airdrop";
    public static boolean removedEntity(String entityType){
        return REMOVED_ENTITIES.contains(entityType)||entityType.endsWith("_CHEST_BOAT")||entityType.endsWith("_CHEST_RAFT");
    }
    /** Armor stands store gear on equipment slots, not in an inventory. */
    public static boolean clearedEquipment(String entityType){return entityType.equals("ARMOR_STAND");}
    public static boolean blockedPlacement(String material){return BLOCKED_PLACEMENT.contains(material);}
    public static boolean blockedEntityPlacement(String entityType){return removedEntity(entityType);}
}
