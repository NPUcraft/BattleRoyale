package com.npucraft.lastsector.loadout;

import java.util.Base64;

/** Immutable, versioned native payload; never retains a mutable ItemStack. */
public record StoredItem(String format, int version, String data) {
    public StoredItem {
        if (!"paper-native".equals(format) || version != 1)
            throw new IllegalArgumentException("Unsupported item format/version: " + format + "/" + version);
        if (data == null || data.isBlank() || data.length() > 2_000_000)
            throw new IllegalArgumentException("Empty or oversized item payload");
        try { Base64.getDecoder().decode(data); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid Base64 item payload", error); }
    }
}
