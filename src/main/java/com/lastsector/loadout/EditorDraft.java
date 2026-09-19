package com.lastsector.loadout;

import java.util.HashMap;
import java.util.Map;

/** Pure virtual brush editor; no real inventory is ever mutated. */
public final class EditorDraft {
    private final String id;
    private final Map<Integer, StoredItem> slots;
    private StoredItem brush;
    private int selected;
    public EditorDraft(LoadoutDefinition source) {
        id = source.id(); slots = new HashMap<>(source.slots()); selected = source.selectedHotbarSlot();
    }
    public void brush(StoredItem item) { brush = item; }
    public void click(int rawSlot, boolean clear) {
        int slot = LoadoutSlotMapping.inventorySlot(rawSlot);
        if (slot < 0) return;
        if (clear || brush == null) slots.remove(slot); else slots.put(slot, brush);
    }
    public void nextHotbar() { selected = (selected + 1) % 9; }
    public LoadoutDefinition snapshot() { return new LoadoutDefinition(id, slots, selected); }
}
