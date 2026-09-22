package com.npucraft.lastsector.paper;
import com.npucraft.lastsector.loot.LootItemResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
public final class NativeLootItems implements LootItemResolver<ItemStack> {
    private Material material(String key) {
        if (!key.startsWith("minecraft:")) throw new IllegalArgumentException("Unsupported loot provider: " + key);
        Material material = Material.matchMaterial(key);
        if (material == null || material.isAir() || !material.isItem()) throw new IllegalArgumentException("Unknown loot item: " + key);
        return material;
    }
    @Override public void validate(String key) { material(key); }
    @Override public ItemStack resolve(String key) { return new ItemStack(material(key)); }
}
