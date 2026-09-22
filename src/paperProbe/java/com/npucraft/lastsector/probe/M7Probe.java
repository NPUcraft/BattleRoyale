package com.npucraft.lastsector.probe;
import org.bukkit.*;import org.bukkit.entity.*;import org.bukkit.command.CommandSender;import org.bukkit.inventory.*;import com.npucraft.lastsector.paper.*;import com.npucraft.lastsector.recovery.*;import java.util.*;
final class M7Probe {
 static void command(TestProbe plugin,CommandSender sender,String[] args){
  var p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));var serializer=new NativeItemSerializer();Object value;
  switch(args[0]){
   case "m7lobby" -> value=PaperDurablePlayers.pure(new PaperPlayerIsolation(plugin,serializer).capture(p.getUniqueId()));
   case "m7ack" -> {p.teleport(Objects.requireNonNull(Bukkit.getWorld("world")).getSpawnLocation());p.getPersistentDataContainer().set(new NamespacedKey("lastsector","restored_generation"),org.bukkit.persistence.PersistentDataType.STRING,args[2]);p.saveData();value=Map.of("acknowledged",true);}
   case "m7match" -> value=new PaperBodySnapshots(serializer).player(p);
   case "m7worldseed" -> {var w=p.getWorld();w.getBlockAt(3,-60,3).setType(Material.DIAMOND_BLOCK);w.getBlockAt(4,-60,3).setType(Material.CHEST);((org.bukkit.block.Chest)w.getBlockAt(4,-60,3).getState()).getInventory().setItem(0,new ItemStack(Material.GOLD_INGOT,17));var item=w.dropItem(new Location(w,5,-59,3),new ItemStack(Material.NETHERITE_INGOT,9));item.setUnlimitedLifetime(true);item.setPickupDelay(Integer.MAX_VALUE);value=Map.of("seeded",true);}
   case "m7worldcheck" -> {var w=p.getWorld();value=Map.of("block",w.getBlockAt(3,-60,3).getType().name(),"chest",((org.bukkit.block.Chest)w.getBlockAt(4,-60,3).getState()).getInventory().getItem(0).getAmount(),"ground",w.getEntitiesByClass(Item.class).stream().anyMatch(i->i.getItemStack().getType()==Material.NETHERITE_INGOT && i.getItemStack().getAmount()==9),"autosave",w.isAutoSave());}
   case "m7box" -> {var inventory=p.getOpenInventory().getTopInventory();if(!(inventory.getHolder() instanceof PaperDeathBoxes.View))throw new IllegalStateException("Open DeathBox first");var contents=new HashMap<Integer,com.npucraft.lastsector.loadout.StoredItem>();for(int i=0;i<54;i++){var item=serializer.store(inventory.getItem(i));if(item!=null)contents.put(i,item);}value=contents;}
   case "m7boxtake" -> {var inventory=p.getOpenInventory().getTopInventory();if(!(inventory.getHolder() instanceof PaperDeathBoxes.View))throw new IllegalStateException("Open DeathBox first");inventory.setItem(Integer.parseInt(args[2]),null);value=Map.of("removed",true);}
   case "m7entities" -> {var w=p.getWorld();value=Map.of("bodies",w.getEntitiesByClass(Villager.class).stream().filter(e->e.getPersistentDataContainer().has(new NamespacedKey("lastsector","offline_player"))).count(),"boxes",w.getEntitiesByClass(Interaction.class).stream().filter(e->e.getPersistentDataContainer().has(new NamespacedKey("lastsector","deathbox_id"))).count());}
   default -> throw new IllegalArgumentException(args[0]);
  }
  sender.sendMessage("M7 JSON="+new SnapshotCodec().encode(value).json());
 }
}
