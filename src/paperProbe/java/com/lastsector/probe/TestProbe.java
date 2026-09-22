package com.lastsector.probe;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.damage.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.*;
/** Opt-in integration harness using public Paper APIs. Never shipped in LastSector.jar. */
public final class TestProbe extends JavaPlugin implements Listener {
    private String rejectTeleport;
    private final M4Probe m4=new M4Probe(this);
    private final M5Probe m5=new M5Probe(this);
    private final M6Probe m6=new M6Probe(this);
    @EventHandler
    public void isolatedWorld(org.bukkit.event.world.WorldLoadEvent event) {
        if(!event.getWorld().getName().startsWith("plugins/LastSector/runtime/")) return;
        if(Boolean.getBoolean("lastsector.probe.m4")) { m4.fixture(event.getWorld()); return; }
        // Test fixture only: template-persisted difficulty/mobs must not randomly kill protocol test clients.
        event.getWorld().setDifficulty(Difficulty.PEACEFUL);
        event.getWorld().setGameRule(GameRule.DO_MOB_SPAWNING,false);
        event.getWorld().setGameRule(GameRule.NATURAL_REGENERATION,false);
        event.getWorld().getLivingEntities().stream().filter(entity->entity instanceof Mob).forEach(Entity::remove);
    }
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getPluginManager().registerEvents(m5,this);
        getServer().getPluginManager().registerEvents(m6,this);
        getCommand("lsprobe").setExecutor((sender,command,label,args)-> {
            try {
                if(args[0].startsWith("m7")){M7Probe.command(this,sender,args);return true;}
                if(args[0].startsWith("m4")) { m4.command(sender,args); return true; }
                if(args[0].startsWith("m5")) { m5.command(sender,args); return true; }
                if(args[0].startsWith("m6")) { m6.command(sender,args); return true; }
                if(args[0].equals("player")) {
                    var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));
                    var world=player.getWorld(); var loc=player.getLocation();
                    sender.sendMessage("PROBE player="+player.getName()+" world="+world.getName()+" seed="+world.getSeed()
                            +" uuid="+world.getUID()+" atSpawn="+(loc.distanceSquared(world.getSpawnLocation())<16)
                            +" x="+loc.getX()+" y="+loc.getY()+" z="+loc.getZ()+" health="+player.getHealth()
                            +" floor="+loc.clone().subtract(0,1,0).getBlock().getType()+" feet="+loc.getBlock().getType()
                            +" head="+loc.clone().add(0,1,0).getBlock().getType()+" tick="+Bukkit.getCurrentTick());
                } else if(args[0].equals("worlds")) sender.sendMessage("PROBE worlds="+getServer().getWorlds().stream().map(World::getName).toList());
                else if(args[0].equals("disable")) {
                    getServer().getPluginManager().disablePlugin(getServer().getPluginManager().getPlugin("LastSector")); sender.sendMessage("PROBE disabled");
                } else if(args[0].equals("reject")) { rejectTeleport=args[1]; sender.sendMessage("PROBE reject armed"); }
                else if(args[0].equals("position")) {
                    var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));
                    player.teleportAsync(new Location(player.getWorld(),Double.parseDouble(args[2]),Double.parseDouble(args[3]),Double.parseDouble(args[4])))
                            .thenAccept(ok->sender.sendMessage("PROBE positioned="+ok));
                } else if(args[0].equals("health")) {
                    var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));
                    player.setHealth(Double.parseDouble(args[2])); player.setFoodLevel(20); player.setSaturation(0); sender.sendMessage("PROBE health set");
                } else if(args[0].equals("armor")) {
                    var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));
                    ItemStack[] armor={new ItemStack(Material.DIAMOND_BOOTS),new ItemStack(Material.DIAMOND_LEGGINGS),
                            new ItemStack(Material.DIAMOND_CHESTPLATE),new ItemStack(Material.DIAMOND_HELMET)};
                    for(var item:armor) item.addEnchantment(Enchantment.PROTECTION,4);
                    player.getInventory().setArmorContents(armor);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,20000,4));
                    sender.sendMessage("PROBE armored resistance=4");
                } else if(args[0].equals("hit")) {
                    String kind=args[1]; Player attacker=Objects.requireNonNull(getServer().getPlayerExact(args[2]));
                    Player victim=Objects.requireNonNull(getServer().getPlayerExact(args[3]));
                    victim.setGameMode(GameMode.SURVIVAL); victim.setHealth(20); victim.setNoDamageTicks(0); victim.setFireTicks(0);
                    victim.getInventory().setArmorContents(new ItemStack[4]); victim.removePotionEffect(PotionEffectType.RESISTANCE);
                    double detail=-1;
                    switch(kind) {
                        case "melee" -> victim.damage(4,attacker);
                        case "arrow" -> {
                            Arrow arrow=victim.getWorld().spawn(victim.getLocation().add(0,4,0),Arrow.class);
                            arrow.setShooter(attacker); victim.damage(4,DamageSource.builder(DamageType.ARROW).withDirectEntity(arrow).withCausingEntity(attacker).build()); arrow.remove();
                        }
                        case "tnt" -> {
                            TNTPrimed tnt=victim.getWorld().spawn(victim.getLocation().add(0,4,0),TNTPrimed.class);
                            tnt.setSource(attacker); victim.damage(4,DamageSource.builder(DamageType.PLAYER_EXPLOSION).withDirectEntity(tnt).withCausingEntity(attacker).build()); tnt.remove();
                        }
                        case "natural" -> victim.damage(4,DamageSource.builder(DamageType.FALL).build());
                        case "mob" -> {
                            var difficulty=victim.getWorld().getDifficulty();
                            victim.getWorld().setDifficulty(Difficulty.NORMAL);
                            Zombie mob=victim.getWorld().spawn(victim.getLocation().add(0,4,0),Zombie.class);
                            try { victim.damage(4,mob); }
                            finally { mob.remove(); victim.getWorld().setDifficulty(difficulty); }
                        }
                        case "splash" -> {
                            ThrownPotion potion=victim.getWorld().spawn(victim.getLocation().add(0,4,0),ThrownPotion.class);
                            potion.setShooter(attacker);
                            ItemStack item=new ItemStack(Material.SPLASH_POTION);
                            var meta=(org.bukkit.inventory.meta.PotionMeta)item.getItemMeta(); meta.setBasePotionType(PotionType.HARMING); item.setItemMeta(meta); potion.setItem(item);
                            PotionSplashEvent event=new PotionSplashEvent(potion,null,null,null,new HashMap<>(Map.of(victim,1.0)));
                            getServer().getPluginManager().callEvent(event); detail=event.getIntensity(victim); potion.remove();
                        }
                        case "cloud" -> {
                            AreaEffectCloud cloud=victim.getWorld().spawn(victim.getLocation().add(0,4,0),AreaEffectCloud.class);
                            cloud.setSource(attacker); cloud.setBasePotionType(PotionType.HARMING);
                            AreaEffectCloudApplyEvent event=new AreaEffectCloudApplyEvent(cloud,new ArrayList<>(List.of(victim)));
                            getServer().getPluginManager().callEvent(event); detail=event.getAffectedEntities().size(); cloud.remove();
                        }
                        case "fire" -> {
                            var block=victim.getLocation().getBlock(); var old=block.getBlockData();
                            getServer().getPluginManager().callEvent(new BlockIgniteEvent(block,BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL,attacker));
                            block.setType(Material.FIRE,false);
                            var event=new EntityCombustByBlockEvent(block,victim,5f);
                            getServer().getPluginManager().callEvent(event); detail=event.isCancelled()?0:1; block.setBlockData(old,false);
                            getServer().getPluginManager().callEvent(new org.bukkit.event.block.BlockFadeEvent(block,block.getState()));
                        }
                        case "lava","natural-lava","flow" -> {
                            var block=victim.getLocation().getBlock(); var old=block.getBlockData();
                            var source=kind.equals("flow")?block.getRelative(1,0,0):block;
                            var sourceOld=source.getBlockData();
                            if(!kind.equals("natural-lava")) {
                                var event=new org.bukkit.event.player.PlayerBucketEmptyEvent(attacker,source,source,org.bukkit.block.BlockFace.UP,
                                        Material.LAVA_BUCKET,new ItemStack(Material.BUCKET),org.bukkit.inventory.EquipmentSlot.HAND);
                                getServer().getPluginManager().callEvent(event);
                            }
                            source.setType(Material.LAVA,false);
                            if(kind.equals("flow")) getServer().getPluginManager().callEvent(new org.bukkit.event.block.BlockFromToEvent(source,block));
                            block.setType(Material.LAVA,false);
                            victim.damage(4,DamageSource.builder(DamageType.LAVA).build());
                            block.setBlockData(old,false); source.setBlockData(sourceOld,false);
                            getServer().getPluginManager().callEvent(new org.bukkit.event.block.BlockFadeEvent(block,block.getState()));
                            getServer().getPluginManager().callEvent(new org.bukkit.event.block.BlockFadeEvent(source,source.getState()));
                        }
                        default -> throw new IllegalArgumentException(kind);
                    }
                    sender.sendMessage("PROBE hit="+kind+" health="+victim.getHealth()+" detail="+detail);
                }
            } catch(Exception error) { sender.sendMessage("PROBE failed="+error); }
            return true;
        });
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void reject(PlayerTeleportEvent event) {
        if(event.getPlayer().getName().equals(rejectTeleport) && event.getTo().getWorld().getName().startsWith("plugins/")) {
            rejectTeleport=null; event.setCancelled(true); getLogger().info("PROBE rejected landing");
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) {
        var to=event.getTo();
        getLogger().info("PROBE teleport="+event.getPlayer().getName()+" world="+to.getWorld().getName()
                +" atSpawn="+(to.distanceSquared(to.getWorld().getSpawnLocation())<.01)
                +" x="+to.getX()+" y="+to.getY()+" z="+to.getZ()+" tick="+Bukkit.getCurrentTick());
    }
}

