package com.npucraft.battleroyale.listener;
import com.npucraft.battleroyale.combat.PvPHazardTracker;
import com.npucraft.battleroyale.paper.PaperMatches;
import com.npucraft.battleroyale.service.PluginRuntime;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.inventory.EquipmentSlot;
import java.util.*;
/** Cancels only attributable player-vs-player harm within one protected running session. */
public final class PvPProtectionListener implements Listener {
    private final PluginRuntime runtime;
    public PvPProtectionListener(PluginRuntime runtime) { this.runtime=runtime; }
    private PaperMatches matches() { return runtime.matches(); }
    private static PvPHazardTracker.BlockKey key(Block block) {
        return new PvPHazardTracker.BlockKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ());
    }
    private boolean inWorld(PaperMatches.Entry entry,World world) {
        return entry.session.gameWorld().orElseThrow().worldName().equals(world.getName());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player victim)) return;
        matches().activePlayer(victim.getUniqueId()).filter(e -> inWorld(e,victim.getWorld())).ifPresent(entry -> {
            UUID attacker=runtime.provenance().attacker(event,entry);
            if(matches().blocks(entry,attacker,victim.getUniqueId())) event.setCancelled(true);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void combust(EntityCombustEvent event) {
        if(!(event.getEntity() instanceof LivingEntity victim)) return;
        var target=matches().victim(victim);if(target==null)return;var entry=target.entry();
        UUID attacker=null;
        if(event instanceof EntityCombustByEntityEvent entity)attacker=runtime.provenance().owner(entity.getCombuster(),entry);
        if(event instanceof EntityCombustByBlockEvent block && block.getCombuster()!=null)attacker=entry.hazards.owner(key(block.getCombuster()));
        if(attacker==null)attacker=runtime.provenance().contactOwner(victim,entry);
        if(matches().blocks(entry,attacker,target.player()))event.setCancelled(true);else entry.hazards.burning(target.player(),attacker);
    }
    private static boolean harmful(Collection<PotionEffect> effects) {
        return effects.stream().anyMatch(e -> e.getType().getEffectCategory()==org.bukkit.potion.PotionEffectType.Category.HARMFUL);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent event) {
        if(!harmful(event.getPotion().getEffects())) return;
        for(LivingEntity entity:event.getAffectedEntities()) {
            var target=matches().victim(entity);if(target!=null && matches().blocks(target.entry(),runtime.provenance().owner(event.getPotion(),target.entry()),target.player()))event.setIntensity(entity,0);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void lingering(LingeringPotionSplashEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getEntity().getWorld()))
            entry.hazards.entity(event.getAreaEffectCloud().getUniqueId(),runtime.provenance().owner(event.getEntity(),entry));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent event) {
        var cloud=event.getEntity();
        List<PotionEffect> effects=new ArrayList<>(cloud.getCustomEffects());
        if(cloud.getBasePotionType()!=null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        if(!harmful(effects)) return;
        event.getAffectedEntities().removeIf(entity -> {
            var target=matches().victim(entity);return target!=null && matches().blocks(target.entry(),runtime.provenance().owner(cloud,target.entry()),target.player());
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void bucket(PlayerBucketEmptyEvent event) {
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,event.getBlock().getWorld())).ifPresent(entry ->
                entry.hazards.block(key(event.getBlock()),event.getBucket()==Material.LAVA_BUCKET?event.getPlayer().getUniqueId():null));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            entry.hazards.block(key(event.getBlock()),null);
            if(event.getBlock().getType()==Material.TNT && entry.session.players().containsKey(event.getPlayer().getUniqueId()))
                entry.hazards.block(key(event.getBlock()),event.getPlayer().getUniqueId());
        }
    }
    /** TNT detonates the instant it is lit instead of running the vanilla four-second fuse. */
    static boolean ignition(Material type) { return type==Material.FLINT_AND_STEEL||type==Material.FIRE_CHARGE; }
    /** Placed TNT is a thrown charge: it primes the moment it lands on a short fuse, no flint needed. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void autoIgniteTnt(BlockPlaceEvent event) {
        if(event.getBlockPlaced().getType()!=Material.TNT) return;
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,event.getBlockPlaced().getWorld())).ifPresent(entry -> {
            event.setCancelled(true);
            var location=event.getBlockPlaced().getLocation().add(.5,0,.5);
            event.getBlockPlaced().getWorld().spawn(location,TNTPrimed.class,tnt -> tnt.setSource(event.getPlayer())).setFuseTicks(60);
            var equipment=event.getPlayer().getEquipment();
            if(equipment!=null) {
                var item=event.getHand()==EquipmentSlot.OFF_HAND?equipment.getItemInOffHand():equipment.getItemInMainHand();
                if(item.getType()==Material.TNT) item.setAmount(item.getAmount()-1);
            }
        });
    }
    /** A fire charge is dispenser-grade ordnance: right-click a block (or use the attack swing,
     *  which vanilla clients always send) to launch a small fireball, one charge per shot. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void launchFireCharge(PlayerInteractEvent event) {
        if(event.getItem()==null || event.getItem().getType()!=Material.FIRE_CHARGE) return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK) return;
        // Right-clicking placed TNT still detonates it instead of launching.
        if(event.getAction()==Action.RIGHT_CLICK_BLOCK && event.getClickedBlock()!=null && event.getClickedBlock().getType()==Material.TNT) return;
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,event.getPlayer().getWorld())).ifPresent(entry -> {
            event.setCancelled(true);
            launch(event.getPlayer());
        });
    }
    /** Vanilla clients never send an air use packet for fire charges (Item.use() is PASS), so aiming
     *  at the horizon produced no event at all. The attack swing is always sent — that is the ranged trigger. */
    @EventHandler(priority=EventPriority.MONITOR)
    public void swingFireCharge(PlayerAnimationEvent event) {
        if(event.getAnimationType()!=PlayerAnimationType.ARM_SWING) return;
        var equipment=event.getPlayer().getEquipment();
        if(equipment==null||equipment.getItemInMainHand().getType()!=Material.FIRE_CHARGE) return;
        launch(event.getPlayer());
    }
    /** A launched fire charge detonates on impact like a TNT charge: power 4, terrain damage, attributed. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fireballImpact(ProjectileHitEvent event) {
        if(!(event.getEntity() instanceof SmallFireball fireball)) return;
        if(!(fireball.getShooter() instanceof Player shooter)) return;
        matches().activePlayer(shooter.getUniqueId()).filter(e -> inWorld(e,fireball.getWorld())).ifPresent(entry -> {
            var at=fireball.getLocation();
            fireball.getWorld().createExplosion(at.getX(),at.getY(),at.getZ(),4f,false,true,shooter);
            fireball.remove();
        });
    }
    private static final long SHOT_COOLDOWN=400_000_000L;
    private final Map<UUID,Long> lastShot=new HashMap<>();
    private void launch(Player player) {
        long now=System.nanoTime();
        if(now-lastShot.getOrDefault(player.getUniqueId(),0L)<SHOT_COOLDOWN) return;
        if(matches().activePlayer(player.getUniqueId()).filter(e -> inWorld(e,player.getWorld())).isEmpty()) return;
        lastShot.put(player.getUniqueId(),now);
        player.launchProjectile(SmallFireball.class,player.getEyeLocation().getDirection().normalize().multiply(2.0));
        var equipment=player.getEquipment();
        if(equipment!=null&&equipment.getItemInMainHand().getType()==Material.FIRE_CHARGE) {
            var item=equipment.getItemInMainHand();
            if(item.getAmount()>1) item.setAmount(item.getAmount()-1); else equipment.setItemInMainHand(null);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void detonateTnt(PlayerInteractEvent event) {
        Block block=event.getClickedBlock();
        if(block==null || event.getAction()!=Action.RIGHT_CLICK_BLOCK || block.getType()!=Material.TNT) return;
        if(event.getItem()==null || !ignition(event.getItem().getType())) return;
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,block.getWorld())).ifPresent(entry -> {
            event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY);
            // Vanilla TNT power and terrain damage are preserved; only the fuse is removed.
            block.getWorld().createExplosion(block.getLocation().add(.5,.5,.5),4f,false,true,event.getPlayer());
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void explosiveInteraction(PlayerInteractEvent event) {
        Block block=event.getClickedBlock();
        if(block==null || event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock()==Event.Result.DENY) return;
        if(block.getType()!=Material.RESPAWN_ANCHOR && !Tag.BEDS.isTagged(block.getType())) return;
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,block.getWorld())).ifPresent(entry -> {
            entry.hazards.block(key(block),event.getPlayer().getUniqueId());
            if(block.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
                var direction=bed.getPart()==org.bukkit.block.data.type.Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
                entry.hazards.block(key(block.getRelative(direction)),event.getPlayer().getUniqueId());
            }
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            UUID owner=runtime.provenance().owner(event.getIgnitingEntity(),entry);
            if(owner==null && event.getIgnitingBlock()!=null) owner=entry.hazards.owner(key(event.getIgnitingBlock()));
            entry.hazards.block(key(event.getBlock()),owner);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spread(BlockSpreadEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld()))
            entry.hazards.spread(key(event.getSource()),key(event.getBlock()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void flow(BlockFromToEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld()))
            entry.hazards.spread(key(event.getBlock()),key(event.getToBlock()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void prime(TNTPrimeEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            UUID owner=runtime.provenance().owner(event.getPrimingEntity(),entry);
            if(owner==null && event.getPrimingBlock()!=null) owner=entry.hazards.owner(key(event.getPrimingBlock()));
            if(owner!=null) entry.hazards.block(key(event.getBlock()),owner);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spawn(EntitySpawnEvent event) {
        if(!(event.getEntity() instanceof TNTPrimed tnt)) return;
        for(var entry:matches().runningEntries()) if(inWorld(entry,tnt.getWorld())) {
            UUID owner=runtime.provenance().owner(tnt,entry);
            if(owner==null) owner=entry.hazards.owner(key(tnt.getLocation().getBlock()));
            entry.hazards.entity(tnt.getUniqueId(),owner);
            entry.hazards.block(key(tnt.getLocation().getBlock()),null);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void launch(ProjectileLaunchEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getEntity().getWorld()))
            entry.hazards.entity(event.getEntity().getUniqueId(),runtime.provenance().owner(event.getEntity(),entry));
    }
    private void clear(Block block) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,block.getWorld())) entry.hazards.block(key(block),null);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void broken(BlockBreakEvent event) { clear(event.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fade(BlockFadeEvent event) { clear(event.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fill(PlayerBucketFillEvent event) { clear(event.getBlock()); }
}

