package com.npucraft.battleroyale.loot;
import java.util.List;
public record MapLoot(List<ContainerLootPoint> containers, List<LootArea> areas) {
    public MapLoot { containers = List.copyOf(containers); areas = List.copyOf(areas); }
}
