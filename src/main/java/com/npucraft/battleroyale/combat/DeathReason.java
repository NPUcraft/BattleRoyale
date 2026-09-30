package com.npucraft.battleroyale.combat;
import java.util.*;
public record DeathReason(DamageOrigin origin,Optional<UUID> killer,Set<UUID> assists,boolean direct) {
    public DeathReason { Objects.requireNonNull(origin); Objects.requireNonNull(killer); assists=Set.copyOf(assists); }
}
