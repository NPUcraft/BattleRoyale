package com.npucraft.battleroyale.api.item;
/**
 * Versioned item serialization boundary; an adapter may bind T to Bukkit ItemStack.
 * Implementations preserve custom item metadata and document server-thread requirements.
 * Returned bytes must be independently owned; unsupported/corrupt payloads must fail explicitly.
 */
public interface ItemSerializer<T> {
    /** Stable adapter format identifier, persisted alongside the payload. */
    String formatId();
    /** Version of new payloads; readers must explicitly reject unsupported versions. */
    int formatVersion();
    /** Serializes a nonnull item to a new byte array, preserving adapter-specific metadata. */
    byte[] serialize(T item);
    /** Restores an independent item from bytes in this adapter's format. */
    T deserialize(byte[] payload, int formatVersion);
}
