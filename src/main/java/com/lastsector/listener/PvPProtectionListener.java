package com.lastsector.listener;
import com.lastsector.combat.PvPHazardTracker;
import com.lastsector.paper.PaperMatches;
import com.lastsector.service.PluginRuntime;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import java.util.*;
/** Cancels only attributable player-vs-player harm within one protected running session. */
public final class PvPProtectionListener implements Listener {
    private final PluginRuntime runtime;
    public PvPProtectionListener(PluginRuntime runtime) { this.runtime=runtime; }
    private PaperMatches matches() { return runtime.matches(); }
    private static PvPHazardTracker.BlockKey key(Block block) {
        return new PvPHazardTracker.BlockKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ());
    }
    private UUID owner(Entity entity,PaperMatches.Entry entry) {
        if(entity==null) return null;
        if(entity instanceof Player) return entity.getUniqueId();
        if(entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player.getUniqueId();
        if(entity instanceof Firework firework && firework.getSpawningEntity()!=null) return firework.getSpawningEntity();
        if(entity instanceof LightningStrike lightning && lightning.getCausingPlayer()!=null) return lightning.getCausingPlayer().getUniqueId();
        if(entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player.getUniqueId();
        if(entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player.getUniqueId();
        return entry.hazards.entityOwner(entity.getUniqueId());
    }
    private boolean inWorld(PaperMatches.Entry entry,World world) {
        return entry.session.gameWorld().orElseThrow().worldName().equals(world.getName());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player victim)) return;
        matches().protectedPlayer(victim.getUniqueId()).filter(e -> inWorld(e,victim.getWorld())).ifPresent(entry -> {
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
            if(matches().blocks(entry,attacker,victim.getUniqueId())) event.setCancelled(true);
        });
    }
    private UUID contactOwner(Player player,PaperMatches.Entry entry) {
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
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void combust(EntityCombustEvent event) {
        if(!(event.getEntity() instanceof Player victim)) return;
        matches().protectedPlayer(victim.getUniqueId()).filter(e -> inWorld(e,victim.getWorld())).ifPresent(entry -> {
            UUID attacker=null;
            if(event instanceof EntityCombustByEntityEvent entity) attacker=owner(entity.getCombuster(),entry);
            if(event instanceof EntityCombustByBlockEvent block && block.getCombuster()!=null) attacker=entry.hazards.owner(key(block.getCombuster()));
            if(attacker==null) attacker=contactOwner(victim,entry);
            if(matches().blocks(entry,attacker,victim.getUniqueId())) event.setCancelled(true);
            else entry.hazards.burning(victim.getUniqueId(),attacker);
        });
    }
    private static boolean harmful(Collection<PotionEffect> effects) {
        return effects.stream().anyMatch(e -> e.getType().getEffectCategory()==org.bukkit.potion.PotionEffectType.Category.HARMFUL);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent event) {
        if(!harmful(event.getPotion().getEffects())) return;
        for(LivingEntity entity:event.getAffectedEntities()) if(entity instanceof Player victim)
            matches().protectedPlayer(victim.getUniqueId()).ifPresent(entry -> {
                if(matches().blocks(entry,owner(event.getPotion(),entry),victim.getUniqueId())) event.setIntensity(victim,0);
            });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void lingering(LingeringPotionSplashEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getEntity().getWorld()))
            entry.hazards.entity(event.getAreaEffectCloud().getUniqueId(),owner(event.getEntity(),entry));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent event) {
        var cloud=event.getEntity();
        List<PotionEffect> effects=new ArrayList<>(cloud.getCustomEffects());
        if(cloud.getBasePotionType()!=null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        if(!harmful(effects)) return;
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim &&
                matches().protectedPlayer(victim.getUniqueId()).map(entry ->
                        matches().blocks(entry,owner(cloud,entry),victim.getUniqueId())).orElse(false));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void bucket(PlayerBucketEmptyEvent event) {
        matches().protectedPlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,event.getBlock().getWorld())).ifPresent(entry ->
                entry.hazards.block(key(event.getBlock()),event.getBucket()==Material.LAVA_BUCKET?event.getPlayer().getUniqueId():null));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            entry.hazards.block(key(event.getBlock()),null);
            if(event.getBlock().getType()==Material.TNT && entry.session.players().containsKey(event.getPlayer().getUniqueId()))
                entry.hazards.block(key(event.getBlock()),event.getPlayer().getUniqueId());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void explosiveInteraction(PlayerInteractEvent event) {
        Block block=event.getClickedBlock();
        if(block==null || event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock()==Event.Result.DENY) return;
        if(block.getType()!=Material.RESPAWN_ANCHOR && !Tag.BEDS.isTagged(block.getType())) return;
        matches().protectedPlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,block.getWorld())).ifPresent(entry -> {
            entry.hazards.block(key(block),event.getPlayer().getUniqueId());
            if(block.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
                var direction=bed.getPart()==org.bukkit.block.data.type.Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
                entry.hazards.block(key(block.getRelative(direction)),event.getPlayer().getUniqueId());
            }
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            UUID owner=owner(event.getIgnitingEntity(),entry);
            if(owner==null && event.getIgnitingBlock()!=null) owner=entry.hazards.owner(key(event.getIgnitingBlock()));
            entry.hazards.block(key(event.getBlock()),owner);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spread(BlockSpreadEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getBlock().getWorld()))
            entry.hazards.spread(key(event.getSource()),key(event.getBlock()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void flow(BlockFromToEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getBlock().getWorld()))
            entry.hazards.spread(key(event.getBlock()),key(event.getToBlock()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void prime(TNTPrimeEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            UUID owner=owner(event.getPrimingEntity(),entry);
            if(owner==null && event.getPrimingBlock()!=null) owner=entry.hazards.owner(key(event.getPrimingBlock()));
            if(owner!=null) entry.hazards.block(key(event.getBlock()),owner);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spawn(EntitySpawnEvent event) {
        if(!(event.getEntity() instanceof TNTPrimed)) return;
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getEntity().getWorld())) {
            UUID owner=owner(event.getEntity(),entry);
            if(owner==null) owner=entry.hazards.owner(key(event.getLocation().getBlock()));
            entry.hazards.entity(event.getEntity().getUniqueId(),owner);
            entry.hazards.block(key(event.getLocation().getBlock()),null);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void launch(ProjectileLaunchEvent event) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,event.getEntity().getWorld()))
            entry.hazards.entity(event.getEntity().getUniqueId(),owner(event.getEntity(),entry));
    }
    private void clear(Block block) {
        for(var entry:matches().protectedEntries()) if(inWorld(entry,block.getWorld())) entry.hazards.block(key(block),null);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void broken(BlockBreakEvent event) { clear(event.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fade(BlockFadeEvent event) { clear(event.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fill(PlayerBucketFillEvent event) { clear(event.getBlock()); }
}

