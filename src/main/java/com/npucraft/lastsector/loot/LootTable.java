package com.npucraft.lastsector.loot;

import java.util.*;
import java.util.random.RandomGenerator;

/** Weighted sampling with replacement. Limits bound configuration-driven work. */
public record LootTable(String id, int minRolls, int maxRolls, List<Entry> entries) {
    public record Entry(String item, long weight, int minAmount, int maxAmount) {
        public Entry {
            if (item == null || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid item key: " + item);
            if (weight <= 0 || minAmount < 1 || maxAmount < minAmount || maxAmount > 4096)
                throw new IllegalArgumentException("Invalid weight or amount (maximum 4096)");
        }
    }
    public record Roll(String item, int amount) {}
    public LootTable {
        entries = List.copyOf(entries);
        if (minRolls < 0 || maxRolls < minRolls || maxRolls > 128 || (maxRolls > 0 && entries.isEmpty()))
            throw new IllegalArgumentException("Invalid loot rolls (maximum 128)");
        long total = 0;
        for (Entry entry : entries) total = Math.addExact(total, entry.weight());
        if ((long)maxRolls * entries.stream().mapToInt(Entry::maxAmount).max().orElse(0) > 4096)
            throw new IllegalArgumentException("A loot roll batch may produce at most 4096 items");
    }
    public List<Roll> roll(RandomGenerator random) {
        int count = random.nextInt(minRolls, maxRolls + 1);
        long total = entries.stream().mapToLong(Entry::weight).sum();
        List<Roll> result = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            long choice = random.nextLong(total);
            for (Entry entry : entries) {
                if (choice < entry.weight()) {
                    result.add(new Roll(entry.item(), random.nextInt(entry.minAmount(), entry.maxAmount() + 1))); break;
                }
                choice -= entry.weight();
            }
        }
        return List.copyOf(result);
    }
    public static List<Integer> split(int amount, int maxStack) {
        if (amount < 0 || maxStack < 1) throw new IllegalArgumentException("Invalid stack split");
        List<Integer> result = new ArrayList<>();
        while (amount > 0) { int size = Math.min(amount, maxStack); result.add(size); amount -= size; }
        return List.copyOf(result);
    }
}
