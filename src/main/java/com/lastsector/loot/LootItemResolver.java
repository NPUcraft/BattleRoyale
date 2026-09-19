package com.lastsector.loot;

/** Providers validate keys at load time; item creation belongs on the server thread. */
public interface LootItemResolver<T> {
    void validate(String key);
    T resolve(String key);
}
