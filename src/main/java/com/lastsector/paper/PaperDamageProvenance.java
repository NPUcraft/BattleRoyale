package com.lastsector.paper;
import com.lastsector.combat.PvPHazardTracker;
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
    public com.lastsector.combat.DamageOrigin origin(org.bukkit.damage.DamageSource source) {
        return switch(source.getDamageType().getKey().getKey()) {
            case "player_attack" -> com.lastsector.combat.DamageOrigin.PLAYER_MELEE;
            case "arrow","trident","fireworks","fireball","unattributed_fireball","wither_skull","thrown" -> com.lastsector.combat.DamageOrigin.PROJECTILE;
            case "explosion","player_explosion","bad_respawn_point" -> com.lastsector.combat.DamageOrigin.EXPLOSION;
            case "in_fire","on_fire","campfire","hot_floor" -> com.lastsector.combat.DamageOrigin.FIRE;
            case "lava" -> com.lastsector.combat.DamageOrigin.LAVA;
            case "fall","fly_into_wall","stalagmite" -> com.lastsector.combat.DamageOrigin.FALL;
            case "drown" -> com.lastsector.combat.DamageOrigin.DROWNING;
            case "out_of_world" -> com.lastsector.combat.DamageOrigin.VOID;
            case "magic","indirect_magic","wither","dragon_breath" -> com.lastsector.combat.DamageOrigin.MAGIC;
            case "mob_attack","mob_attack_no_aggro","mob_projectile","sonic_boom" -> com.lastsector.combat.DamageOrigin.MOB;
            default -> com.lastsector.combat.DamageOrigin.OTHER;
        };
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
    public UUID contactOwner(Player player,PaperMatches.Entry entry) {
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
        Player victim=(Player)event.getEntity();
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
                attacker=entry.hazards.burningOwner(victim.getUniqueId());
        return attacker;
    }
}
