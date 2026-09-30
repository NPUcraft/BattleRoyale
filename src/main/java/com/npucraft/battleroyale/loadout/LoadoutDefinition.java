package com.npucraft.battleroyale.loadout;

import java.util.Map;

/** Storage 0..35, boots 36, leggings 37, chestplate 38, helmet 39, offhand 40. */
public record LoadoutDefinition(String id, Map<Integer, StoredItem> slots, int selectedHotbarSlot) {
    public LoadoutDefinition {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid loadout id");
        slots = Map.copyOf(slots);
        if (slots.keySet().stream().anyMatch(slot -> slot < 0 || slot > 40))
            throw new IllegalArgumentException("Loadout slot must be 0..40");
        if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8) throw new IllegalArgumentException("Selected hotbar slot must be 0..8");
    }
}
