package com.npucraft.battleroyale.loot;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Full-list coverage for the live-match storage guard: every listed container/entity is handled. */
class StorageGuardPolicyTest {
    @ParameterizedTest @CsvSource({
        "SHULKER_BOX","WHITE_SHULKER_BOX","ORANGE_SHULKER_BOX","MAGENTA_SHULKER_BOX","LIGHT_BLUE_SHULKER_BOX",
        "YELLOW_SHULKER_BOX","LIME_SHULKER_BOX","PINK_SHULKER_BOX","GRAY_SHULKER_BOX","LIGHT_GRAY_SHULKER_BOX",
        "CYAN_SHULKER_BOX","PURPLE_SHULKER_BOX","BLUE_SHULKER_BOX","BROWN_SHULKER_BOX","GREEN_SHULKER_BOX",
        "RED_SHULKER_BOX","BLACK_SHULKER_BOX","BARREL","CRAFTER","DISPENSER","DROPPER","ENDER_CHEST"})
    void everyGuardedContainerIsBlockedForOpenAndPlacement(String material){
        assertTrue(StorageGuardPolicy.blockedContainer(material),material);
        assertTrue(StorageGuardPolicy.blockedPlacement(material),material);
    }
    @ParameterizedTest @ValueSource(strings={"CHEST","TRAPPED_CHEST","COPPER_CHEST","HOPPER","FURNACE","STONE","AIR","DIAMOND_BLOCK"})
    void automaticContainerTargetsAndPlainBlocksStayAllowed(String material){
        assertFalse(StorageGuardPolicy.blockedContainer(material),material);
        assertFalse(StorageGuardPolicy.blockedPlacement(material),material);
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
    @Test void everyShulkerMaterialAndChestCarrierEntityInTheRegistryIsCovered(){
        for(var material:Material.values())if(material.name().endsWith("_SHULKER_BOX")||material.name().equals("SHULKER_BOX"))assertTrue(StorageGuardPolicy.blockedContainer(material.name()),material.name());
        for(var type:EntityType.values()){
            var name=type.name();
            if(name.endsWith("_CHEST_BOAT")||name.endsWith("_CHEST_RAFT"))assertTrue(StorageGuardPolicy.removedEntity(name),name);
        }
    }
}
