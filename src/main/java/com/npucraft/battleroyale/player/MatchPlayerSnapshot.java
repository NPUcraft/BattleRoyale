package com.npucraft.battleroyale.player;

import com.npucraft.battleroyale.loadout.StoredItem;
import org.bukkit.GameMode;
import org.bukkit.potion.PotionEffect;
import java.util.*;

/** Native immutable item payloads; no live inventory, Player or mutable ItemStack references. */
public record MatchPlayerSnapshot(Map<Integer,StoredItem> inventory, Map<Integer,StoredItem> enderChest,
        StoredItem cursor, int selected, int level, float progress, int totalExperience, GameMode mode,
        double health, int food, float saturation, float exhaustion, List<PotionEffect> effects,
        int fireTicks, float fallDistance, double absorption, boolean allowFlight, boolean flying) {
    public MatchPlayerSnapshot { inventory=Map.copyOf(inventory); enderChest=Map.copyOf(enderChest); effects=List.copyOf(effects); }
}
