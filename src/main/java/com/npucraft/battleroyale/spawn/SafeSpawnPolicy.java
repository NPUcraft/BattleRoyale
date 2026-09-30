package com.npucraft.battleroyale.spawn;
public final class SafeSpawnPolicy {
    private SafeSpawnPolicy() {}
    public record Cell(boolean fullSupport,boolean passable,boolean liquid,boolean hazardous,boolean leaves) {}
    public static boolean safe(Cell floor,Cell feet,Cell head) {
        return floor.fullSupport() && !floor.hazardous() && !floor.liquid() && !floor.leaves()
                && clear(feet) && clear(head);
    }
    private static boolean clear(Cell cell) { return cell.passable() && !cell.liquid() && !cell.hazardous() && !cell.leaves(); }
}

