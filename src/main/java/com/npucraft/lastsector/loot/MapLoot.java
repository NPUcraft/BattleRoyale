package com.npucraft.lastsector.loot;
import java.util.List;
public record MapLoot(List<ContainerLootPoint> containers, List<LootArea> areas) {
    public MapLoot { containers = List.copyOf(containers); areas = List.copyOf(areas); }
}
