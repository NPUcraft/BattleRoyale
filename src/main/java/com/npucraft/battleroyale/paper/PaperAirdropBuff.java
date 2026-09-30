package com.npucraft.battleroyale.paper;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import com.npucraft.battleroyale.loot.BeaconBuffPolicy;
import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Filter native beacon pulses; never remove or force-replace any player's existing effect. */
public final class PaperAirdropBuff {
    private final Predicate<Player> participant;
    public PaperAirdropBuff(Predicate<Player> participant){this.participant=Objects.requireNonNull(participant);}
    public void apply(BeaconEffectEvent event,boolean active){
        if(event.isCancelled())return;
        event.setCancelled(true); // An unexpected callback failure must never leak a native buff.
        if(!active||!event.isPrimary())return;
        Player player=event.getPlayer();if(!participant.test(player))return;
        var location=player.getLocation();var block=event.getBlock();
        boolean sameWorld=player.getWorld().getUID().equals(block.getWorld().getUID());
        double dx=location.getX()-(block.getX()+.5),dy=location.getY()-(block.getY()+.5),dz=location.getZ()-(block.getZ()+.5);
        var current=player.getPotionEffect(PotionEffectType.SPEED);
        boolean allowed=BeaconBuffPolicy.allows(active,event.isPrimary(),sameWorld,true,player.isOnline(),player.isDead(),
                player.getGameMode()==GameMode.SPECTATOR,dx*dx+dy*dy+dz*dz,current==null?-1:current.getAmplifier(),current==null?0:current.getDuration());
        if(!allowed){event.setCancelled(true);return;}
        event.setEffect(new PotionEffect(PotionEffectType.SPEED,BeaconBuffPolicy.DURATION_TICKS,BeaconBuffPolicy.AMPLIFIER,true,false,true));
        event.setCancelled(false);
    }
}
