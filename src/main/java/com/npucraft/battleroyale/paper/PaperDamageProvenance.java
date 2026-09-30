package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.combat.PvPHazardTracker;
import com.npucraft.battleroyale.combat.VanillaDamageOrigins;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import java.util.*;
/** Shared public-API source resolution for protection and combat. No duplicate owner rules. */
public final class PaperDamageProvenance {
    public UUID attacker(org.bukkit.damage.DamageSource source,PaperMatches.Entry entry) {
        UUID result=owner(source.getCausingEntity(),entry); return result!=null?result:owner(source.getDirectEntity(),entry);
    }
    public com.npucraft.battleroyale.combat.DamageOrigin origin(org.bukkit.damage.DamageSource source) {
        return VanillaDamageOrigins.fromKey(source.getDamageType().getKey().getKey());
    }
    private static PvPHazardTracker.BlockKey key(Block b) { return new PvPHazardTracker.BlockKey(b.getWorld().getUID(),b.getX(),b.getY(),b.getZ()); }
    public UUID owner(Entity entity,PaperMatches.Entry entry) {
        if(entity==null) return null;
        if(entity instanceof Player) return entity.getUniqueId();
        if(entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player.getUniqueId();
        if(entity instanceof Firework firework && firework.getSpawningEntity()!=null) return firework.getSpawningEntity();
        if(entity instanceof LightningStrike lightning && lightning.getCausingPlayer()!=null) return lightning.getCausingPlayer().getUniqueId();
        if(entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player.getUniqueId();
        if(entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player.getUniqueId();
        return entry.hazards.entityOwner(entity.getUniqueId());
    }
    public UUID contactOwner(LivingEntity player,PaperMatches.Entry entry) {
        var box=player.getBoundingBox();
        for(int x=(int)Math.floor(box.getMinX());x<=Math.floor(box.getMaxX());x++)
            for(int y=(int)Math.floor(box.getMinY());y<=Math.floor(box.getMaxY());y++)
                for(int z=(int)Math.floor(box.getMinZ());z<=Math.floor(box.getMaxZ());z++) {
                    Block block=player.getWorld().getBlockAt(x,y,z);
                    if(Set.of(Material.LAVA,Material.FIRE,Material.SOUL_FIRE).contains(block.getType())) {
                        UUID owner=entry.hazards.owner(key(block)); if(owner!=null) return owner;
                    }
                }
        return null;
    }
    public UUID attacker(EntityDamageEvent event,PaperMatches.Entry entry) {
        LivingEntity victim=(LivingEntity)event.getEntity();
            UUID attacker=owner(event.getDamageSource().getCausingEntity(),entry);
            if(attacker==null) attacker=owner(event.getDamageSource().getDirectEntity(),entry);
            if(attacker==null && event instanceof EntityDamageByEntityEvent byEntity) attacker=owner(byEntity.getDamager(),entry);
            if(attacker==null && event instanceof EntityDamageByBlockEvent byBlock) {
                Block block=byBlock.getDamager();
                if(block==null && byBlock.getDamagerBlockState()!=null) block=byBlock.getDamagerBlockState().getBlock();
                if(block!=null) attacker=entry.hazards.owner(key(block));
            }
            if(attacker==null && Set.of(EntityDamageEvent.DamageCause.LAVA,EntityDamageEvent.DamageCause.FIRE).contains(event.getCause()))
                attacker=contactOwner(victim,entry);
            if(attacker==null && event.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK)
                attacker=entry.hazards.burningOwner(entry.offline!=null && entry.offline.entity(victim)!=null?entry.offline.entity(victim).player():victim.getUniqueId());
        return attacker;
    }
}
