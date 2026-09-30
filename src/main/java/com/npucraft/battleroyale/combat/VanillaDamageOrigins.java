package com.npucraft.battleroyale.combat;

/** Classifies vanilla damage key paths without requiring a running Bukkit registry. */
public final class VanillaDamageOrigins {
    private VanillaDamageOrigins() {}

    public static DamageOrigin fromKey(String key) {
        return switch (key) {
            case "player_attack", "spear", "mace_smash" -> DamageOrigin.PLAYER_MELEE;
            case "arrow", "trident", "fireworks", "fireball", "unattributed_fireball", "wither_skull", "thrown", "wind_charge" -> DamageOrigin.PROJECTILE;
            case "explosion", "player_explosion", "bad_respawn_point" -> DamageOrigin.EXPLOSION;
            case "in_fire", "on_fire", "campfire", "hot_floor", "sulfur_cube_hot" -> DamageOrigin.FIRE;
            case "lava" -> DamageOrigin.LAVA;
            case "fall", "fly_into_wall", "stalagmite" -> DamageOrigin.FALL;
            case "drown" -> DamageOrigin.DROWNING;
            case "out_of_world" -> DamageOrigin.VOID;
            case "magic", "indirect_magic", "wither", "dragon_breath" -> DamageOrigin.MAGIC;
            case "mob_attack", "mob_attack_no_aggro", "mob_projectile", "sonic_boom" -> DamageOrigin.MOB;
            default -> DamageOrigin.OTHER;
        };
    }
}
