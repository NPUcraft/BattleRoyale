package com.npucraft.battleroyale.loadout;

/** Stable virtual editor mapping; controls are never item slots. */
public final class LoadoutSlotMapping {
    public static final int SAVE = 49, CANCEL = 53, HOTBAR = 45;
    private LoadoutSlotMapping() {}
    public static int inventorySlot(int raw) {
        if (raw >= 0 && raw < 36) return raw;
        return switch (raw) { case 36 -> 39; case 37 -> 38; case 38 -> 37; case 39 -> 36; case 40 -> 40; default -> -1; };
    }
}
