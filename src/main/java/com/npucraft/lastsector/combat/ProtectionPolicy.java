package com.npucraft.lastsector.combat;
import java.util.Set;
import java.util.UUID;
public final class ProtectionPolicy {
    private ProtectionPolicy() {}
    public static boolean blocks(ProtectionWindow window,long now,Set<UUID> participants,UUID attacker,UUID victim) {
        return window.active(now) && attacker!=null && !attacker.equals(victim)
                && participants.contains(attacker) && participants.contains(victim);
    }
}

