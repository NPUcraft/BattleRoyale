package com.npucraft.battleroyale.paper;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Visible feedback while a combatant keeps taking zone damage. ZoneDamage stays the only damage source.
 *  Nausea only warps the view; WITHER would add its own damage over time and is therefore rejected. */
public final class ZoneDamageFeedback {
    /** Longer than the one-second damage pulse so the warp is continuous while the player stays outside. */
    static final int DURATION_TICKS=60;
    /** The pulse is already throttled to once per second; every second pulse also plays the sound. */
    static final int EMIT_INTERVAL=2;
    private ZoneDamageFeedback(){}
    public static boolean audible(long pulseCount){return pulseCount%EMIT_INTERVAL==0;}
    public static void apply(Player player,long pulseCount) {
        // Overwrite instead of stacking, so repeated pulses cannot accumulate an unbounded duration.
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,DURATION_TICKS,0,false,true,true));
        // No actionbar here: the zone BossBar already reads "Outside zone" and the navigation bar would overwrite it.
        if(!audible(pulseCount))return;
        player.playSound(player.getLocation(),Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,SoundCategory.PLAYERS,.5f,.8f);
    }
    /** Called when a player stops being a live combatant, so no warp survives into spectating or the lobby. */
    public static void clear(Player player){player.removePotionEffect(PotionEffectType.NAUSEA);}
}
