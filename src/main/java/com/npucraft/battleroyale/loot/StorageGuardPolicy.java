package com.npucraft.battleroyale.loot;

import java.util.Set;

/** Explicit container/entity list for a live match. Plain chests stay openable because the
 * automatic-container feature refills them; every other inventory carrier is blocked or removed. */
public final class StorageGuardPolicy {
    private StorageGuardPolicy() {}
    /** Block materials whose inventory must never be opened or placed during a match. */
    public static final Set<String> BLOCKED_CONTAINERS=Set.of("BARREL","CRAFTER","DISPENSER","DROPPER","ENDER_CHEST");
    /** Entity types removed from the map because they carry equipment or an inventory. */
    public static final Set<String> REMOVED_ENTITIES=Set.of("ARMOR_STAND","ITEM_FRAME","GLOW_ITEM_FRAME","CHEST_MINECART","HOPPER_MINECART");
    /** Air-drop crates share this marker and remain the only openable barrel. */
    public static final String AIRDROP_KEY="airdrop";
    /** Chests and trapped chests are deliberately excluded: automatic container loot owns them. */
    public static boolean blockedContainer(String material){return BLOCKED_CONTAINERS.contains(material)||shulkerBox(material);}
    public static boolean shulkerBox(String material){return material.equals("SHULKER_BOX")||material.endsWith("_SHULKER_BOX");}
    public static boolean removedEntity(String entityType){
        return REMOVED_ENTITIES.contains(entityType)||entityType.endsWith("_CHEST_BOAT")||entityType.endsWith("_CHEST_RAFT");
    }
    /** Armor stands store gear on equipment slots, not in an inventory. */
    public static boolean clearedEquipment(String entityType){return entityType.equals("ARMOR_STAND");}
    public static boolean blockedPlacement(String material){return blockedContainer(material);}
    public static boolean blockedEntityPlacement(String entityType){return removedEntity(entityType);}
}
