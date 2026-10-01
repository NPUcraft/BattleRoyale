package com.npucraft.battleroyale.loot;
import java.util.List;
public record MapLoot(List<ContainerLootPoint> containers, List<LootArea> areas) {
    public static final int MAX_GROUND_REQUESTS=2048;
    public MapLoot {
        containers=List.copyOf(containers);areas=List.copyOf(areas);
        if(areas.stream().mapToLong(LootArea::maxSpawns).sum()>MAX_GROUND_REQUESTS)
            throw new IllegalArgumentException("Map ground loot candidate budget exceeds 2048");
    }
}
