package com.lastsector.paper;

import com.lastsector.api.item.ItemSerializer;
import com.lastsector.loadout.StoredItem;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import java.util.Base64;

/** Native Paper item bytes retain all supported metadata, components and PDC. */
public final class NativeItemSerializer implements ItemSerializer<ItemStack> {
    @Override public String formatId() { return "paper-native"; }
    @Override public int formatVersion() { return 1; }
    @Override public byte[] serialize(ItemStack item) {
        mainThread();
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Cannot serialize an empty item");
        return item.serializeAsBytes();
    }
    @Override public ItemStack deserialize(byte[] payload, int version) {
        mainThread();
        if (version != formatVersion()) throw new IllegalArgumentException("Unsupported native item version: " + version);
        try {
            ItemStack result = ItemStack.deserializeBytes(payload);
            if (result == null || result.getType().isAir() || result.getAmount() <= 0) throw new IllegalArgumentException("Empty native item");
            return result;
        } catch (RuntimeException error) { throw new IllegalArgumentException("Cannot decode native item payload", error); }
    }
    public StoredItem store(ItemStack item) {
        return item == null || item.getType().isAir() ? null : new StoredItem(formatId(), formatVersion(), Base64.getEncoder().encodeToString(serialize(item)));
    }
    public ItemStack item(StoredItem stored) {
        return stored == null ? null : deserialize(Base64.getDecoder().decode(stored.data()), stored.version());
    }
    private void mainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Item API requires server thread"); }
}
