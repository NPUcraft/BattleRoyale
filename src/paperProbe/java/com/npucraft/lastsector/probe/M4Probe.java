package com.npucraft.lastsector.probe;

import com.npucraft.lastsector.paper.NativeItemSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import java.util.*;

/** Disposable integration fixture using only public APIs. */
@SuppressWarnings({"deprecation","removal"})
final class M4Probe {
    private final JavaPlugin plugin;
    private final NativeItemSerializer serializer=new NativeItemSerializer();
    private final Map<UUID,ItemStack[]> originals=new HashMap<>();
    private final Map<UUID,List<UUID>> oldEntities=new HashMap<>(),preservedEntities=new HashMap<>();
    M4Probe(JavaPlugin plugin) { this.plugin=plugin; }
    void fixture(World world) {
        world.setDifficulty(Difficulty.NORMAL);
        for(int x:new int[]{0,2,4,6,8}) {
            world.getBlockAt(x,-60,0).setType(x==2?Material.BARREL:Material.CHEST,false);
            var state=world.getBlockAt(x,-60,0).getState();
            ((InventoryHolder)state).getInventory().addItem(new ItemStack(Material.DIAMOND,20));
            if(state instanceof Chest chest && x==0) { chest.setLootTable(LootTables.SIMPLE_DUNGEON.getLootTable()); chest.update(); }
        }
        // An outside-InitialZone point remains sanitized and empty when first inspected.
        world.getBlockAt(600,-60,0).setType(Material.BARREL,false);
        ((InventoryHolder)world.getBlockAt(600,-60,0).getState()).getInventory().addItem(new ItemStack(Material.DIAMOND,20));
        List<UUID> old=new ArrayList<>(),preserved=new ArrayList<>();
        Location location=new Location(world,10,-60,0);
        old.add(world.dropItem(location,new ItemStack(Material.DIAMOND)).getUniqueId());
        old.add(world.spawn(location,ExperienceOrb.class,orb->orb.setExperience(7)).getUniqueId());
        old.add(world.spawn(location,Cow.class,cow->cow.setAI(false)).getUniqueId());
        old.add(world.spawn(location,Zombie.class,zombie->{zombie.setAI(false); zombie.setShouldBurnInDay(false);}).getUniqueId());
        preserved.add(world.spawn(location,Villager.class,villager->{villager.setAI(false);villager.setInvulnerable(true);villager.setCanPickupItems(false);}).getUniqueId());
        preserved.add(world.spawn(location,ArmorStand.class).getUniqueId());
        preserved.add(world.spawn(location,TextDisplay.class).getUniqueId());
        StorageMinecart cart=world.spawn(location,StorageMinecart.class); cart.getInventory().addItem(new ItemStack(Material.DIAMOND,10)); preserved.add(cart.getUniqueId());
        world.getBlockAt(12,-60,0).setType(Material.STONE,false);
        ItemFrame frame=world.spawn(new Location(world,12,-60,1),ItemFrame.class,f->{f.setFixed(true);f.setInvulnerable(true);}); preserved.add(frame.getUniqueId());
        for(int x:new int[]{15,16}) {
            Block block=world.getBlockAt(x,-60,4); block.setType(Material.CHEST,false);
            var data=(org.bukkit.block.data.type.Chest)block.getBlockData(); data.setFacing(BlockFace.NORTH);
            data.setType(x==15?org.bukkit.block.data.type.Chest.Type.LEFT:org.bukkit.block.data.type.Chest.Type.RIGHT); block.setBlockData(data,false);
            ((Chest)block.getState()).getBlockInventory().addItem(new ItemStack(Material.DIAMOND,20));
        }
        check(((Chest)world.getBlockAt(15,-60,4).getState()).getInventory().getSize()==54,"double chest fixture");
        oldEntities.put(world.getUID(),old); preservedEntities.put(world.getUID(),preserved);
        plugin.getLogger().info("M4 fixture created world="+world.getName()+" old="+old.size()+" preserved="+preserved.stream().map(id->id+":"+plugin.getServer().getEntity(id).getType()).toList());
    }
    private ItemStack richItem() {
        ItemStack item=new ItemStack(Material.DIAMOND_SWORD);
        item.editMeta(meta->{
            meta.displayName(Component.text("M4 native sword")); meta.lore(List.of(Component.text("Full metadata survives")));
            meta.addEnchant(Enchantment.SHARPNESS,4,true); ((org.bukkit.inventory.meta.Damageable)meta).setDamage(37);
            meta.setCustomModelData(1234); meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"original"),PersistentDataType.STRING,"preserved");
        }); return item;
    }
    void command(CommandSender sender,String[] args) {
        if(args[0].equals("m4roundtrip")) {
            ItemStack sword=richItem(),potion=new ItemStack(Material.POTION);
            potion.editMeta(PotionMeta.class,meta->{meta.setBasePotionType(PotionType.LONG_SWIFTNESS);meta.addCustomEffect(new PotionEffect(PotionEffectType.LUCK,1234,2),true);});
            for(ItemStack item:List.of(sword,potion)) check(item.equals(serializer.item(serializer.store(item))),"native roundtrip");
            boolean rejected=false; try { serializer.deserialize(new byte[]{1,2,3},1); } catch(IllegalArgumentException expected) { rejected=true; }
            check(rejected,"corrupt native payload accepted"); sender.sendMessage("M4 roundtrip=true"); return;
        }
        Player player=Objects.requireNonNull(plugin.getServer().getPlayerExact(args[1])); World world=player.getWorld();
        switch(args[0]) {
            case "m4seed" -> {
                player.setInvulnerable(true); // Test clients remain stationary while natural spawning stays enabled.
                player.getInventory().clear(); player.getInventory().setItem(0,richItem()); player.getInventory().setItem(7,new ItemStack(Material.EMERALD,13));
                player.getInventory().setHelmet(new ItemStack(Material.GOLDEN_HELMET)); player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
                player.getInventory().setHeldItemSlot(7); player.setLevel(12); player.setExp(.25f); player.setTotalExperience(400); player.setGameMode(GameMode.CREATIVE);
                player.setHealth(12); player.setFoodLevel(14); player.setSaturation(3);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,12000,2));
                player.getEnderChest().clear(); player.getEnderChest().setItem(2,new ItemStack(Material.NETHERITE_INGOT,3));
                originals.put(player.getUniqueId(),Arrays.stream(player.getInventory().getContents()).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new));
                sender.sendMessage("M4 seeded=true");
            }
            case "m4original" -> {
                if(!Arrays.equals(originals.get(player.getUniqueId()),player.getInventory().getContents())) {
                    // M8 intentionally replaces restored carried Lobby items with canonical controls; all other M4 state checks remain.
                    var controls=Map.of(0,"rooms",1,"autojoin",4,"profile",7,"leaderboard",8,"shop");
                    for(int slot=0;slot<player.getInventory().getSize();slot++) {
                        var item=player.getInventory().getItem(slot);String expected=controls.get(slot);
                        if(expected==null)check(item==null||item.getType().isAir(),"unexpected noncanonical Lobby item "+slot);
                        else check(item!=null && expected.equals(item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("lastsector","lobby_action"),PersistentDataType.STRING)),"canonical Lobby control differs "+slot);
                    }
                }
                check(player.getLevel()==12 && player.getExp()==.25f && player.getTotalExperience()==400,"original XP differs");
                check(player.getGameMode()==GameMode.CREATIVE && player.getInventory().getHeldItemSlot()==7,"mode/selection differs");
                check(player.getHealth()==12 && player.getFoodLevel()==14 && player.getSaturation()==3,"original health/food differs: "+player.getHealth()+"/"+player.getFoodLevel()+"/"+player.getSaturation());
                check(player.hasPotionEffect(PotionEffectType.SPEED) && player.getPotionEffect(PotionEffectType.SPEED).getAmplifier()==2,"original potion missing");
                check(player.getEnderChest().getItem(2)!=null && player.getEnderChest().getItem(2).getAmount()==3,"ender chest differs");
                sender.sendMessage("M4 original=true world="+world.getName());
            }
            case "m4match" -> {
                check(player.getGameMode()==GameMode.SURVIVAL && player.getLevel()==0 && player.getTotalExperience()==0,"match normalization");
                check(player.getHealth()==20 && player.getFoodLevel()==20 && !player.hasPotionEffect(PotionEffectType.SPEED),"health/food/potion not normalized");
                check(player.getInventory().getItem(0)!=null && player.getInventory().getItem(0).equals(richItem()),"shared loadout missing");
                check(empty(player.getInventory().getItem(7)) && empty(player.getInventory().getHelmet()) && empty(player.getInventory().getItemInOffHand()),"old inventory leaked: slot7="+player.getInventory().getItem(7)+" helmet="+player.getInventory().getHelmet()+" offhand="+player.getInventory().getItemInOffHand());
                check(player.getEnderChest().isEmpty(),"old ender items leaked"); sender.sendMessage("M4 match=true");
            }
            case "m4sanitize" -> {
                world.getChunkAt(0,0).getEntities();
                for(UUID id:oldEntities.get(world.getUID())) check(plugin.getServer().getEntity(id)==null,"template entity survived: "+id);
                for(UUID id:preservedEntities.get(world.getUID())) {
                    Entity entity=plugin.getServer().getEntity(id); check(entity!=null,"decoration/villager missing: "+id);
                    if(entity instanceof InventoryHolder holder) check(holder.getInventory().isEmpty(),"entity inventory not cleared");
                }
                check(((Chest)world.getBlockAt(0,-60,0).getState()).getLootTable()==null,"vanilla loot table survived");
                check(((InventoryHolder)world.getBlockAt(2,-60,0).getState()).getInventory().isEmpty(),"unconfigured barrel not cleared");
                check(world.getGameRuleValue(GameRule.DO_MOB_SPAWNING),"natural spawning was changed");
                check(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) && world.getGameRuleValue(GameRule.DO_WEATHER_CYCLE),"time/weather changed");
                sender.sendMessage("M4 sanitized=true");
            }
            case "m4loot" -> {
                for(int x=-1;x<=1;x++) for(int z=-1;z<=0;z++) world.getChunkAt(x,z).getEntities();
                Inventory chest=((InventoryHolder)world.getBlockAt(0,-60,0).getState()).getInventory();
                check(chest.contains(Material.BREAD),"container loot absent"); check(!chest.contains(Material.DIAMOND),"template chest content survived");
                var marker=new NamespacedKey(Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("LastSector")),"ground_loot_session");
                List<Item> ground=world.getEntitiesByClass(Item.class).stream().filter(item->item.getPersistentDataContainer().has(marker,PersistentDataType.STRING)).toList();
                check(ground.stream().mapToInt(i->i.getItemStack().getAmount()).sum()==8,"ground loot absent: " + ground.size()); check(ground.stream().allMatch(Item::isUnlimitedLifetime),"ground loot expires");
                Inventory combined=((Chest)world.getBlockAt(15,-60,4).getState()).getInventory();
                check(combined.all(Material.BREAD).values().stream().mapToInt(ItemStack::getAmount).sum()==4,"double chest filled twice/cleared");
                for(Item item:ground) check(Math.abs(item.getLocation().getX())<=11 && Math.abs(item.getLocation().getZ())<=11,"ground outside area");
                sender.sendMessage("M4 loot=true ground="+ground.size());
            }
            case "m4empty" -> { check(player.getInventory().isEmpty(),"future loadout did not update"); sender.sendMessage("M4 empty=true"); }
            case "m4gui" -> {
                var view=player.getOpenInventory();
                for(var click:List.of(org.bukkit.event.inventory.ClickType.SHIFT_LEFT,org.bukkit.event.inventory.ClickType.SHIFT_RIGHT,
                        org.bukkit.event.inventory.ClickType.NUMBER_KEY,org.bukkit.event.inventory.ClickType.MIDDLE,org.bukkit.event.inventory.ClickType.DOUBLE_CLICK,
                        org.bukkit.event.inventory.ClickType.DROP,org.bukkit.event.inventory.ClickType.CONTROL_DROP,org.bukkit.event.inventory.ClickType.SWAP_OFFHAND)) {
                    var event=new org.bukkit.event.inventory.InventoryClickEvent(view,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,81,click,org.bukkit.event.inventory.InventoryAction.UNKNOWN,0);
                    plugin.getServer().getPluginManager().callEvent(event); check(event.isCancelled(),"GUI transfer allowed: "+click);
                }
                var creative=new org.bukkit.event.inventory.InventoryCreativeEvent(view,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,0,new ItemStack(Material.DIAMOND));
                plugin.getServer().getPluginManager().callEvent(creative); check(creative.isCancelled(),"creative transfer allowed");
                var drag=new org.bukkit.event.inventory.InventoryDragEvent(view,new ItemStack(Material.DIAMOND),new ItemStack(Material.DIAMOND,2),false,Map.of(0,new ItemStack(Material.DIAMOND),81,new ItemStack(Material.DIAMOND)));
                plugin.getServer().getPluginManager().callEvent(drag); check(drag.isCancelled(),"drag allowed");
                sender.sendMessage("M4 gui-actions=true");
            }
            case "m4unload" -> {
                Chunk chunk=world.getChunkAt(60,60); chunk.getEntities();
                Location location=new Location(world,965,-60,965);
                Item item=world.dropItem(location,new ItemStack(Material.EMERALD)); UUID itemId=item.getUniqueId();
                Cow cow=world.spawn(location,Cow.class,c->c.setAI(false)); UUID cowId=cow.getUniqueId();
                UUID worldId=world.getUID(); world.unloadChunkRequest(60,60);
                // getChunkAt adds Paper's temporary request ticket; allow it to expire before unloading.
                new org.bukkit.scheduler.BukkitRunnable() {
                    int attempts;
                    @Override public void run() {
                        World current=plugin.getServer().getWorld(worldId);
                        if(current==null) { cancel(); sender.sendMessage("PROBE failed=fixture world unloaded"); return; }
                        if(current.isChunkLoaded(60,60) && !current.unloadChunk(60,60,true)) {
                            if(++attempts>=20) {cancel();sender.sendMessage("PROBE failed=fixture chunk cannot unload");} return;
                        }
                        cancel(); current.getChunkAt(60,60).getEntities();
                        sender.sendMessage(plugin.getServer().getEntity(itemId)!=null && plugin.getServer().getEntity(cowId)!=null
                                ?"M4 unload-reload=true":"PROBE failed=runtime entities lost on actual reload");
                    }
                }.runTaskTimer(plugin,100,20);
            }
            case "m4break" -> {
                world.getBlockAt(15,-60,4).breakNaturally(); world.getBlockAt(16,-60,4).breakNaturally();
                check(world.getEntitiesByClass(Item.class).stream().anyMatch(i->i.getItemStack().getType()==Material.BREAD && !i.isUnlimitedLifetime()),"vanilla chest drops absent");
                sender.sendMessage("M4 break=true");
            }
            case "m4once" -> {
                var chunk=world.getChunkAt(0,0); Inventory chest=((InventoryHolder)world.getBlockAt(0,-60,0).getState()).getInventory(); chest.clear();
                Item fresh=world.dropItem(new Location(world,9,-60,2),new ItemStack(Material.EMERALD)); Cow cow=world.spawn(new Location(world,9,-60,2),Cow.class);
                plugin.getServer().getPluginManager().callEvent(new ChunkLoadEvent(chunk,false));
                plugin.getServer().getPluginManager().callEvent(new EntitiesLoadEvent(chunk,Arrays.asList(chunk.getEntities())));
                check(fresh.isValid() && cow.isValid(),"runtime entity cleaned twice"); check(chest.isEmpty(),"chest refilled");
                player.getInventory().addItem(new ItemStack(Material.DIAMOND,64)); player.giveExp(20); sender.sendMessage("M4 once=true");
            }
            case "m4portal" -> {
                Location from=player.getLocation(),to=from.clone().add(1,0,0); boolean active=!world.getName().equals("world");
                PlayerPortalEvent portal=new PlayerPortalEvent(player,from,to,PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
                plugin.getServer().getPluginManager().callEvent(portal); check(portal.isCancelled()==active,"player portal routing");
                PlayerTeleportEvent pearl=new PlayerTeleportEvent(player,from,to,PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
                plugin.getServer().getPluginManager().callEvent(pearl); check(!pearl.isCancelled(),"pearl blocked");
                PlayerTeleportEvent chorus=new PlayerTeleportEvent(player,from,to,PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT);
                plugin.getServer().getPluginManager().callEvent(chorus); check(!chorus.isCancelled(),"chorus blocked");
                Cow cow=world.spawn(from.clone().add(0,3,0),Cow.class); EntityPortalEvent entity=new EntityPortalEvent(cow,from,to);
                plugin.getServer().getPluginManager().callEvent(entity); check(entity.isCancelled()==active,"entity portal routing"); cow.remove();
                PortalCreateEvent create=new PortalCreateEvent(List.of(),world,player,PortalCreateEvent.CreateReason.FIRE);
                plugin.getServer().getPluginManager().callEvent(create); check(create.isCancelled()==active,"portal creation routing");
                sender.sendMessage("M4 portals=true active="+active);
            }
            default -> throw new IllegalArgumentException("Unknown M4 probe");
        }
    }
    private void check(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
    private boolean empty(ItemStack item) { return item==null || item.getType().isAir(); }
}
