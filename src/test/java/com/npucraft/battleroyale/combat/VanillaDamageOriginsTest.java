package com.npucraft.battleroyale.combat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaDamageOriginsTest {
    @ParameterizedTest
    @CsvSource({
            "spear, PLAYER_MELEE",
            "mace_smash, PLAYER_MELEE",
            "wind_charge, PROJECTILE",
            "sulfur_cube_hot, FIRE"
    })
    void modernWeaponsAndHazardsHaveSpecificDeathReasons(String key, DamageOrigin expected) {
        assertEquals(expected, VanillaDamageOrigins.fromKey(key));
    }

    @ParameterizedTest
    @CsvSource({
            "player_attack, PLAYER_MELEE",
            "arrow, PROJECTILE",
            "player_explosion, EXPLOSION",
            "on_fire, FIRE",
            "lava, LAVA",
            "fall, FALL",
            "drown, DROWNING",
            "out_of_world, VOID",
            "indirect_magic, MAGIC",
            "sonic_boom, MOB"
    })
    void existingDeathReasonsRemainStable(String key, DamageOrigin expected) {
        assertEquals(expected, VanillaDamageOrigins.fromKey(key));
    }

    @Test
    void futureOrCustomDamageKeysUseFallback() {
        assertEquals(DamageOrigin.OTHER, VanillaDamageOrigins.fromKey("future_damage_type"));
    }
}
