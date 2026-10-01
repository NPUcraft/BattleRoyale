package com.npucraft.battleroyale.probe;
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
/** Opt-in integration harness using public Paper APIs. Never shipped in BattleRoyale.jar. */
public final class TestProbe extends JavaPlugin implements Listener {
    private String rejectTeleport;
    private final M4Probe m4=new M4Probe(this);
    private final M5Probe m5=new M5Probe(this);
    private final M6Probe m6=new M6Probe(this);
    private final Paper26Probe p26=new Paper26Probe(this);
    private final Rc3Probe rc3=new Rc3Probe(this);
    private final Rc4Probe rc4=new Rc4Probe(this);
    private final Rc5Probe rc5=new Rc5Probe(this);
    private final Rc6AirdropProbe rc6Airdrop=new Rc6AirdropProbe(this);
    private final Rc6LootProbe rc6Loot=new Rc6LootProbe(this);
    private final Rc6LobbyProbe rc6Lobby=new Rc6LobbyProbe(this);
    private final Rc6EconomyProbe rc6Economy=new Rc6EconomyProbe(this);
    private final Rc7UiProbe rc7Ui=new Rc7UiProbe(this);
    private final Rc7LootProbe rc7Loot=new Rc7LootProbe(this);
    private final Rc7AirdropProbe rc7Airdrop=new Rc7AirdropProbe(this);
    private final Rc7HorseCelebrationProbe rc7Horses=new Rc7HorseCelebrationProbe(this);
    private final Rc8LocaleProbe rc8Locale=new Rc8LocaleProbe(this);
    private final Rc8LobbyProbe rc8Lobby=new Rc8LobbyProbe(this);
    private final Rc10QualityProbe rc10Quality=new Rc10QualityProbe(this);
    private final Rc10GroundProbe rc10Ground=new Rc10GroundProbe(this);
    private final Rc11VotingProbe rc11Voting=new Rc11VotingProbe(this);
    private final Rc9LootProbe rc9Loot=new Rc9LootProbe(this);
    private final FlightDeploymentProbe flight=new FlightDeploymentProbe(this);
    private final AirdropBuffProbe beaconBuff=new AirdropBuffProbe(this);
    @EventHandler
    public void isolatedWorld(org.bukkit.event.world.WorldLoadEvent event) {
        if(!event.getWorld().getKey().getNamespace().equals("battleroyale_game")) return;
        if(Boolean.getBoolean("battleroyale.probe.m4")) { m4.fixture(event.getWorld()); return; }
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
        getCommand("brprobe").setExecutor((sender,command,label,args)-> {
            try {
                if(args.length > 0 && args[0].equals("p26rc10quality")) { rc10Quality.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc10ground")) { rc10Ground.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc11voting")) { rc11Voting.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc9loot")) { rc9Loot.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26flight")) { flight.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc8locale")) { rc8Locale.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc8lobby")) { rc8Lobby.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26beaconbuff")) { beaconBuff.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc7ui")) { rc7Ui.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc7loot")) { rc7Loot.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc7airdrop")) { rc7Airdrop.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc7horses")) { rc7Horses.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc6airdrop")) { rc6Airdrop.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc6loot")) { rc6Loot.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc6lobby")) { rc6Lobby.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc6economy")) { rc6Economy.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc5")) { rc5.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc4")) { rc4.command(sender,args); return true; }
                if(args.length > 0 && args[0].equals("p26rc3")) { rc3.command(sender,args); return true; }
                if(args.length > 0 && args[0].startsWith("p26")) { p26.command(sender,args); return true; }
                if(args[0].equals("m9abuse")) {
                    var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));
                    var view=player.getOpenInventory();var pm=getServer().getPluginManager();int checks=0;
                    for(var click:java.util.List.of(org.bukkit.event.inventory.ClickType.SHIFT_LEFT,org.bukkit.event.inventory.ClickType.NUMBER_KEY,org.bukkit.event.inventory.ClickType.DOUBLE_CLICK,org.bukkit.event.inventory.ClickType.MIDDLE,org.bukkit.event.inventory.ClickType.DROP,org.bukkit.event.inventory.ClickType.SWAP_OFFHAND)) {
                        var event=new org.bukkit.event.inventory.InventoryClickEvent(view,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,0,click,org.bukkit.event.inventory.InventoryAction.UNKNOWN,0);
                        pm.callEvent(event);if(!event.isCancelled())throw new IllegalStateException("GUI abuse not cancelled: "+click);checks++;
                    }
                    var drag=new org.bukkit.event.inventory.InventoryDragEvent(view,new org.bukkit.inventory.ItemStack(Material.STONE),new org.bukkit.inventory.ItemStack(Material.STONE),false,java.util.Map.of(0,new org.bukkit.inventory.ItemStack(Material.STONE)));
                    pm.callEvent(drag);if(!drag.isCancelled())throw new IllegalStateException("GUI drag not cancelled");checks++;
                    var creative=new org.bukkit.event.inventory.InventoryCreativeEvent(view,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,0,new org.bukkit.inventory.ItemStack(Material.DIAMOND));
                    pm.callEvent(creative);if(!creative.isCancelled())throw new IllegalStateException("Creative inventory mutation not cancelled");checks++;
                    sender.sendMessage("M9 abuse cancelled="+checks);return true;
                }
                if(args[0].equals("m9interact")){var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));int slot=Integer.parseInt(args[2]);player.getInventory().setHeldItemSlot(slot);var block=player.getWorld().getBlockAt(Integer.parseInt(args[4]),Integer.parseInt(args[5]),Integer.parseInt(args[6]));if(args.length>7)block.setType(Material.valueOf(args[7]));var event=new org.bukkit.event.player.PlayerInteractEvent(player,args[3].equals("left")?org.bukkit.event.block.Action.LEFT_CLICK_BLOCK:org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,player.getInventory().getItemInMainHand(),block,org.bukkit.block.BlockFace.UP,org.bukkit.inventory.EquipmentSlot.HAND);getServer().getPluginManager().callEvent(event);sender.sendMessage("M9 interaction cancelled="+event.isCancelled());return true;}
                if(args[0].equals("m8visual")){var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));sender.sendMessage("M8 displays="+player.getWorld().getEntitiesByClass(BlockDisplay.class).stream().map(d->d.getBlock().getMaterial()).toList());return true;}
                if(args[0].equals("m8money")) {
                    var player=Objects.requireNonNull(getServer().getPlayerExact(args[1]));
                    com.npucraft.battleroyale.api.economy.EconomyProvider provider=switch(args[2]) {
                        case "coinsengine" -> new com.npucraft.battleroyale.economy.LegacyCoinsEngineEconomyProvider(getServer(),"coins");
                        case "excellenteconomy" -> new com.npucraft.battleroyale.economy.ExcellentEconomyProvider(getServer(),"coins");
                        case "vault" -> new com.npucraft.battleroyale.economy.VaultEconomyProvider(getServer());
                        default -> throw new IllegalArgumentException("Unknown provider");
                    };
                    if(args.length>3 && !provider.deposit(player.getUniqueId(),new java.math.BigDecimal(args[3])))throw new IllegalStateException("Provider deposit declined");
                    sender.sendMessage("M8 balance="+provider.getBalance(player.getUniqueId()));return true;
                }
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
                    getServer().getPluginManager().disablePlugin(getServer().getPluginManager().getPlugin("BattleRoyale")); sender.sendMessage("PROBE disabled");
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
        if(event.getPlayer().getName().equals(rejectTeleport) && event.getTo().getWorld().getKey().getNamespace().equals("battleroyale_game")) {
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
