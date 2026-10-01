package com.npucraft.battleroyale.listener;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TntIgnitionPolicyTest {
    @Test void onlyFireStartingItemsDetonateTntInstantly() {
        assertTrue(PvPProtectionListener.ignition(Material.FLINT_AND_STEEL));
        assertTrue(PvPProtectionListener.ignition(Material.FIRE_CHARGE));
        assertFalse(PvPProtectionListener.ignition(Material.STICK));
        assertFalse(PvPProtectionListener.ignition(Material.FLINT));
        assertFalse(PvPProtectionListener.ignition(Material.TNT));
        assertFalse(PvPProtectionListener.ignition(Material.AIR));
    }
}
