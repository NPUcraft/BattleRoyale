package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.player.PlayerState;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.session.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Visible, proximity-opened supplies. No item/display entity exists before a durable claim. */
public final class PaperGroundSupplies {
    public static final int VISIBLE_RADIUS=48, SOUND_RADIUS=24, OPEN_RADIUS=3, MAX_STACKS_PER_POINT=8;
    private static final int MAX_PENDING=4, MAX_ITEMS=10_000;
    private static final Color TIER_LOW=Color.fromRGB(85,230,190),TIER_MID=Color.fromRGB(255,195,70),TIER_HIGH=Color.fromRGB(255,84,112);
    private final JavaPlugin plugin;
    private final GameSession session;
    private final UUID worldId;
    private final MatchContent content;
    private final NativeLootItems items;
    private final NamespacedKey marker,pointMarker;
    private final Executor io;
    private final GroundSupplyLedger ledger;
    private final List<GroundSupplyLedger.Point> points=new ArrayList<>();
    private final Map<Long,List<GroundSupplyLedger.Point>> cells=new HashMap<>();
    private final Set<Integer> claimed=new HashSet<>();
    private final Map<Integer,Opening> opening=new LinkedHashMap<>();
    private CompletableFuture<Void> writes=CompletableFuture.completedFuture(null);
    private CompletableFuture<Optional<GroundSupplyLedger.Snapshot>> recovery;
    private boolean sealed,closed;
    private int ticks,spawned,itemBudget;
    private final List<GroundSupplyLedger.Point> placed=new ArrayList<>();
    private record Opening(GroundSupplyLedger.Point point,UUID player,CompletableFuture<Boolean> durable){}

    public PaperGroundSupplies(JavaPlugin plugin,GameSession session,MatchContent content,NativeLootItems items,NamespacedKey marker,Executor io){
        this.plugin=plugin;this.session=session;this.content=content;this.items=items;this.marker=marker;this.io=io;
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));
        worldId=world.getUID();pointMarker=new NamespacedKey(plugin,"ground_supply_point");
        ledger=new GroundSupplyLedger(world.getWorldPath(),session.sessionId(),worldId);
    }
    public void plan(int x,int y,int z,LootTable table,long seed){
        if(sealed||closed)throw new IllegalStateException("Supply plan is closed");
        if(points.size()>=MapLoot.MAX_GROUND_REQUESTS)throw new IllegalStateException("Supply point budget exceeded");
        var point=new GroundSupplyLedger.Point(points.size(),x,y,z,table.id(),seed,table.minRolls(),table.maxRolls());
        add(point);
    }
    private void add(GroundSupplyLedger.Point point){
        points.add(point);cells.computeIfAbsent(key(point.x()>>4,point.z()>>4),ignored->new ArrayList<>()).add(point);
    }
    /** Adds a point after seal(); the durable append shares the same IO chain as claims. */
    public void append(int x,int y,int z,LootTable table,long seed){
        if(!sealed||closed)throw new IllegalStateException("Supply plan is open or closed");
        if(points.size()>=MapLoot.MAX_GROUND_REQUESTS)throw new IllegalStateException("Supply point budget exceeded");
        var point=new GroundSupplyLedger.Point(points.size(),x,y,z,table.id(),seed,table.minRolls(),table.maxRolls());
        add(point);
        // Sharing the writer chain guarantees every append reaches disk before any later claim.
        writes=writes.thenRunAsync(()->{try{ledger.append(List.of(point));}catch(IOException error){throw new CompletionException(error);}},io);
    }
    public CompletableFuture<Void> seal(){
        if(sealed||closed)throw new IllegalStateException("Supply plan already sealed");
        sealed=true;var copy=List.copyOf(points);
        writes=writes.thenRunAsync(()->{try{ledger.seal(copy);}catch(IOException error){throw new CompletionException(error);}},io);
        return writes;
    }
    /** Missing files in pre-feature worlds mean no new supplies, never a fresh random plan. */
    public void recover(){
        if(sealed||closed)throw new IllegalStateException("Supply plan already started");
        sealed=true;
        recovery=writes.thenApplyAsync(unused->{try{return ledger.read();}catch(IOException error){throw new CompletionException(error);}},io);
        writes=recovery.thenAccept(unused->{});
    }
    public void tick(){
        var viewers=new ArrayList<Player>();
        for(UUID id:session.players().keySet()){var player=plugin.getServer().getPlayer(id);if(player!=null)viewers.add(player);}
        tick(viewers);
    }
    /** The owning match loop calls this only after its global recovery gate. */
    public void tick(Collection<? extends Player> viewers){
        if(closed||!sealed||session.state()!=GameState.RUNNING)return;
        if(recovery!=null){
            if(!recovery.isDone())return;
            var restored=recovery.join();recovery=null;
            restored.ifPresent(saved->{for(var point:saved.points()){
                if(!content.tables().containsKey(point.table()))throw new IllegalStateException("Unknown recovered supply table: "+point.table());
                if(!session.initialZone().orElseThrow().contains(point.x()+.5,point.z()+.5))throw new IllegalStateException("Recovered supply outside initial zone");
                add(point);
            }claimed.addAll(saved.claimed());itemBudget=claimed.size()*MAX_STACKS_PER_POINT;});
        }
        if(writes.isDone())writes.join();
        var participants=session.players();
        var present=new HashMap<UUID,Player>();for(var player:viewers)if(eligible(player,participants))present.put(player.getUniqueId(),player);
        int emitted=0;
        for(var entry:List.copyOf(opening.values())){
            if(!entry.durable().isDone())continue;
            if(!entry.durable().join()){opening.remove(entry.point().id());continue;}
            // Walking away after a successful claim must not silently destroy the supplies.
            // A temporarily unloaded/blocked point stays pending; no chunk is loaded on its behalf.
            if(emitted>=MAX_PENDING||!safePoint(entry.point()))continue;
            opening.remove(entry.point().id());materialize(present.get(entry.player()),entry.point());emitted++;
        }
        if(++ticks%10!=0)return;
        sweepPlaced();
        for(var player:present.values()){
            var near=nearby(player);int rendered=0;
            for(var point:near){
                if(claimed.contains(point.id()))continue;
                if(rendered++<3)signal(player,point);
                if(opening.size()<64&&opening.values().stream().filter(value->!value.durable().isDone()).count()<MAX_PENDING&&itemBudget+MAX_STACKS_PER_POINT<=MAX_ITEMS&&canOpen(player,point))claim(player,point);
            }
            if(ticks%160==0&&!near.isEmpty()){
                var point=near.getFirst();if(distance(player.getLocation(),point)<=SOUND_RADIUS*SOUND_RADIUS)
                    player.playSound(at(point),Sound.BLOCK_AMETHYST_BLOCK_CHIME,SoundCategory.BLOCKS,.55f,1.3f);
            }
        }
    }
    private boolean eligible(Player player,Map<UUID,com.npucraft.battleroyale.player.GamePlayer> participants){
        var participant=participants.get(player.getUniqueId());
        return participant!=null&&participant.state()==PlayerState.ALIVE&&player.isOnline()&&!player.isDead()
                &&player.getWorld().getUID().equals(worldId)&&player.getGameMode()!=GameMode.SPECTATOR&&player.getGameMode()!=GameMode.CREATIVE;
    }
    private List<GroundSupplyLedger.Point> nearby(Player player){
        Location location=player.getLocation();int x=location.getBlockX()>>4,z=location.getBlockZ()>>4;
        var result=new ArrayList<GroundSupplyLedger.Point>();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            if(!player.getWorld().isChunkLoaded(x+dx,z+dz))continue;
            for(var point:cells.getOrDefault(key(x+dx,z+dz),List.of()))
                if(!claimed.contains(point.id())&&distance(location,point)<=VISIBLE_RADIUS*VISIBLE_RADIUS)result.add(point);
        }
        result.sort(Comparator.comparingDouble(point->distance(location,point)));return result;
    }
    private boolean canOpen(Player player,GroundSupplyLedger.Point point){
        World world=player.getWorld();
        if(distance(player.getLocation(),point)>OPEN_RADIUS*OPEN_RADIUS||!safePoint(point))return false;
        Location eye=player.getEyeLocation(),target=at(point).add(0,.45,0);var direction=target.toVector().subtract(eye.toVector());double length=direction.length();
        // A short ray may cross a corner neighbour: do not let Bukkit load that chunk implicitly.
        for(int x=Math.min(eye.getBlockX()>>4,point.x()>>4);x<=Math.max(eye.getBlockX()>>4,point.x()>>4);x++)
            for(int z=Math.min(eye.getBlockZ()>>4,point.z()>>4);z<=Math.max(eye.getBlockZ()>>4,point.z()>>4);z++)
                if(!world.isChunkLoaded(x,z))return false;
        return length<.05||world.rayTraceBlocks(eye,direction.normalize(),length,FluidCollisionMode.NEVER,true)==null;
    }
    private boolean safePoint(GroundSupplyLedger.Point point){
        World world=plugin.getServer().getWorld(worldId);
        return world!=null&&world.isChunkLoaded(point.x()>>4,point.z()>>4)&&world.getChunkAt(point.x()>>4,point.z()>>4).isEntitiesLoaded()
                &&point.y()>world.getMinHeight()&&point.y()<world.getMaxHeight()
                &&PaperSpawnTerrain.safeItemGround(world.getBlockAt(point.x(),point.y()-1,point.z()),world.getBlockAt(point.x(),point.y(),point.z()));
    }
    private void claim(Player player,GroundSupplyLedger.Point point){
        // Reserve before any asynchronous work: competing players cannot request this point twice.
        claimed.add(point.id());itemBudget+=MAX_STACKS_PER_POINT;
        var durable=writes.thenApplyAsync(unused->{try{return ledger.claim(point.id());}catch(IOException error){throw new CompletionException(error);}},io);
        writes=durable.thenAccept(unused->{});opening.put(point.id(),new Opening(point,player.getUniqueId(),durable));
    }
    /** Looted chests vanish once emptied; foreign or vanished blocks simply drop off the tracking list. */
    private void sweepPlaced(){
        var world=plugin.getServer().getWorld(worldId);if(world==null)return;
        for(var iterator=placed.iterator();iterator.hasNext();){
            var point=iterator.next();
            if(!world.isChunkLoaded(point.x()>>4,point.z()>>4))continue;
            var block=world.getBlockAt(point.x(),point.y(),point.z());
            if(!(block.getState() instanceof Chest chest)||!session.sessionId().toString().equals(chest.getPersistentDataContainer().get(marker,PersistentDataType.STRING))){iterator.remove();continue;}
            if(chest.getInventory().isEmpty()){block.setType(Material.AIR,false);iterator.remove();}
        }
    }
    private void materialize(Player player,GroundSupplyLedger.Point point){
        var world=Objects.requireNonNull(plugin.getServer().getWorld(worldId));
        var source=Objects.requireNonNull(content.tables().get(point.table()));
        var table=new LootTable(source.id(),point.minRolls(),point.maxRolls(),source.entries());var random=new Random(point.seed());
        var stacks=new ArrayList<ItemStack>();int count=0;
        outer:for(var roll:table.roll(random)){
            ItemStack prototype=items.roll(roll.item(),random);
            for(int amount:LootTable.split(roll.amount(),prototype.getMaxStackSize())){
                if(count>=MAX_STACKS_PER_POINT)break outer;
                var stack=prototype.clone();stack.setAmount(amount);stacks.add(stack);count++;
            }
        }
        // A chest, not dropped items: the loot waits for the finder instead of scattering on the ground.
        var block=world.getBlockAt(point.x(),point.y(),point.z());block.setType(Material.CHEST,false);
        var chest=(Chest)block.getState();
        chest.customName(I18n.shared("ground.supply","野外补给","Field supply"));
        chest.getPersistentDataContainer().set(marker,PersistentDataType.STRING,session.sessionId().toString());
        chest.getPersistentDataContainer().set(pointMarker,PersistentDataType.INTEGER,point.id());
        chest.update(true,false);
        var inventory=((Chest)world.getBlockAt(point.x(),point.y(),point.z()).getState()).getInventory();
        List<Integer> slots=new ArrayList<>();for(int i=0;i<inventory.getSize();i++)slots.add(i);Collections.shuffle(slots,random);
        for(int i=0;i<stacks.size()&&i<slots.size();i++)inventory.setItem(slots.get(i),stacks.get(i));
        placed.add(point);spawned+=stacks.size();
        if(player!=null){player.playSound(at(point),Sound.BLOCK_AMETHYST_BLOCK_BREAK,SoundCategory.BLOCKS,.7f,1.15f);
        player.sendMessage(UiText.message(player,"已发现野外补给，附近出现了补给箱！","Field supplies discovered! A supply chest appeared nearby."));}
    }
    private void signal(Player player,GroundSupplyLedger.Point point){
        var center=at(point);
        // Rarity is read by ring count first (1/2/3), colour is only the secondary cue:
        // tables without an authored tier fall back to the legacy region hues.
        var tier=content.groundLoot().tierOf(point.table());
        if(tier==null)tier=point.table().equals(content.regionQuality().builtTable())?"mid":"low";
        var rgb=switch(tier){case "mid"->TIER_MID;case "high"->TIER_HIGH;default->TIER_LOW;};
        int rings=switch(tier){case "mid"->2;case "high"->3;default->1;};
        var dust=new Particle.DustOptions(rgb,1.6f);
        // Concentric rings around the surface block plus a short vertical sparkle column.
        // Per viewer: at most 3 points * (3 rings * 20 dust + 4 end rods) = 204 particles, every half second.
        for(int ring=0;ring<rings;ring++){
            double radius=.85+ring*.5,height=center.getY()+.15+ring*.12;
            for(int i=0;i<20;i++){double angle=i*Math.PI/10;
                player.spawnParticle(Particle.DUST,center.getX()+Math.cos(angle)*radius,height,center.getZ()+Math.sin(angle)*radius,1,0,0,0,0,dust,true);}
        }
        for(int i=0;i<4;i++)player.spawnParticle(Particle.END_ROD,center.getX(),center.getY()+.4+i*.65,center.getZ(),1,.02,.02,.02,0,null,true);
    }
    private Location at(GroundSupplyLedger.Point point){return new Location(Objects.requireNonNull(plugin.getServer().getWorld(worldId)),point.x()+.5,point.y(),point.z()+.5);}
    private static double distance(Location location,GroundSupplyLedger.Point point){double x=location.getX()-point.x()-.5,y=location.getY()-point.y(),z=location.getZ()-point.z()-.5;return x*x+y*y+z*z;}
    private static long key(int x,int z){return ((long)z<<32)|(x&0xffffffffL);}
    public CompletableFuture<Void> stop(){closed=true;opening.clear();removePlacedChests();return writes;}
    /** The runtime owns every chest it placed; stopping removes them all so the world carries no loot litter. */
    private void removePlacedChests(){
        var world=plugin.getServer().getWorld(worldId);
        if(world!=null)for(var point:placed){
            if(!world.isChunkLoaded(point.x()>>4,point.z()>>4))continue;
            var block=world.getBlockAt(point.x(),point.y(),point.z());
            if(block.getState() instanceof Chest chest&&session.sessionId().toString().equals(chest.getPersistentDataContainer().get(marker,PersistentDataType.STRING)))
                block.setType(Material.AIR,false);
        }
        placed.clear();
    }
    public void close(){stop();}
    public int planned(){return points.size();}
    public String diagnostics(){return "ground-supplies="+points.size()+" claimed="+claimed.size()+" pending="+opening.size()+" spawned="+spawned;}
}
