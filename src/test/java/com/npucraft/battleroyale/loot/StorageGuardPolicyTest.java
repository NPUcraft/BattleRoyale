package com.npucraft.battleroyale.loot;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Full-list coverage for the live-match storage guard. Map-native containers are emptied at
 *  world load, so placement is denied ONLY for the exploit vectors (hopper, ender chest);
 *  every other container block in the registry must be placeable. */
class StorageGuardPolicyTest {
    @ParameterizedTest @ValueSource(strings={"HOPPER","ENDER_CHEST"})
    void exploitVectorsStayBlockedFromPlacement(String material){
        assertTrue(StorageGuardPolicy.blockedPlacement(material),material);
    }
    @ParameterizedTest @ValueSource(strings={"FURNACE","BLAST_FURNACE","SMOKER","BREWING_STAND","BARREL","CRAFTER",
        "DISPENSER","DROPPER","LECTERN","DECORATED_POT","CHISELED_BOOKSHELF","BEACON","JUKEBOX",
        "CHEST","TRAPPED_CHEST","COPPER_CHEST","SHULKER_BOX","WHITE_SHULKER_BOX","BLACK_SHULKER_BOX","STONE","DIAMOND_BLOCK"})
    void preClearedContainersArePlaceable(String material){
        assertFalse(StorageGuardPolicy.blockedPlacement(material),material);
    }
    @Test void everyShulkerMaterialInTheRegistryIsPlaceable(){
        for(var material:Material.values())if(material.name().endsWith("_SHULKER_BOX")||material.name().equals("SHULKER_BOX"))
            assertFalse(StorageGuardPolicy.blockedPlacement(material.name()),material.name());
    }
    @Test void onlyExactHopperIsOnTheDenyList(){
        // No registry material may inherit the deny list just by sharing its name
        // (e.g. HOPPER_MINECART is an entity, handled by blockedEntityPlacement instead).
        for(var material:Material.values())if(material.name().contains("HOPPER")&&!material.name().equals("HOPPER"))
            assertFalse(StorageGuardPolicy.blockedPlacement(material.name()),material.name()+" follows the generic rule");
    }
    @ParameterizedTest @ValueSource(strings={"ARMOR_STAND","ITEM_FRAME","GLOW_ITEM_FRAME","CHEST_MINECART","HOPPER_MINECART",
        "OAK_CHEST_BOAT","SPRUCE_CHEST_BOAT","BIRCH_CHEST_BOAT","JUNGLE_CHEST_BOAT","ACACIA_CHEST_BOAT","DARK_OAK_CHEST_BOAT",
        "MANGROVE_CHEST_BOAT","CHERRY_CHEST_BOAT","PALE_OAK_CHEST_BOAT","BAMBOO_CHEST_RAFT"})
    void everyItemBearingEntityIsRemovedAndBlockedFromPlacement(String type){
        assertTrue(StorageGuardPolicy.removedEntity(type),type);
        assertTrue(StorageGuardPolicy.blockedEntityPlacement(type),type);
    }
    @ParameterizedTest @ValueSource(strings={"VILLAGER","COW","ITEM","EXPERIENCE_ORB","TEXT_DISPLAY","INTERACTION","OAK_BOAT","MINECART","PLAYER","HORSE"})
    void unrelatedEntitiesSurvive(String type){
        assertFalse(StorageGuardPolicy.removedEntity(type),type);
        assertFalse(StorageGuardPolicy.blockedEntityPlacement(type),type);
    }
    @Test void armorStandGearIsClearedByEquipmentNotInventory(){
        assertTrue(StorageGuardPolicy.clearedEquipment("ARMOR_STAND"));
        assertFalse(StorageGuardPolicy.clearedEquipment("ITEM_FRAME"));
        assertFalse(StorageGuardPolicy.clearedEquipment("CHEST_MINECART"));
    }
    @Test void everyChestCarrierEntityInTheRegistryIsCovered(){
        for(var type:EntityType.values()){
            var name=type.name();
            if(name.endsWith("_CHEST_BOAT")||name.endsWith("_CHEST_RAFT"))assertTrue(StorageGuardPolicy.removedEntity(name),name);
        }
    }
}
