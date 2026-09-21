package com.lastsector.paper;
import com.lastsector.combat.DeathReason;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.function.Function;
public final class DeathReasonRenderer {
    private DeathReasonRenderer() {}
    public static Component render(DeathReason reason,Function<UUID,String> names) {
        String cause=switch(reason.origin()) {
            case PLAYER_MELEE -> "Combat"; case PROJECTILE -> "Projectile"; case EXPLOSION -> "Explosion";
            case FIRE -> "Fire"; case LAVA -> "Lava"; case FALL -> "Fall"; case ZONE -> "Zone"; case DROWNING -> "Drowning";
            case VOID -> "Void"; case MOB -> "Mob"; case MAGIC -> "Magic"; case OTHER -> "Other";
        };
        return Component.text(reason.killer().map(id->"Killed by "+names.apply(id)+" ("+cause+")").orElse("Died to "+cause));
    }
}
