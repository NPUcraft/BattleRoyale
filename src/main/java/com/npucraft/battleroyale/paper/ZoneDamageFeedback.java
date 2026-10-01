package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.service.UiText;
import java.util.Locale;
import net.kyori.adventure.text.Component;
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
    /** The pulse itself is already throttled to once per second; every second pulse also carries sound and text. */
    static final int EMIT_INTERVAL=2;
    private ZoneDamageFeedback(){}
    public static boolean audible(long pulseCount){return pulseCount%EMIT_INTERVAL==0;}
    /** Text is split out so the throttle and wording stay testable without registry-backed effect/sound enums. */
    public static void apply(Player player,long pulseCount) {
        // Overwrite instead of stacking, so repeated pulses cannot accumulate an unbounded duration.
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,DURATION_TICKS,0,false,true,true));
        if(!audible(pulseCount))return;
        player.sendActionBar(warning(I18n.locale(player)));
        player.playSound(player.getLocation(),Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,SoundCategory.PLAYERS,.5f,.8f);
    }
    static Component warning(Locale locale){return UiText.error(I18n.text(locale,"正在安全区外持续受伤","Taking damage outside the safe zone"));}
    /** Called when a player stops being a live combatant, so no warp survives into spectating or the lobby. */
    public static void clear(Player player){player.removePotionEffect(PotionEffectType.NAUSEA);}
}
