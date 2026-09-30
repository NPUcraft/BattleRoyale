package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.LobbySettings;
import com.npucraft.battleroyale.progression.LobbyRankingRow;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Entity-only addition to a READY lobby: no block, blueprint, marker or backup is changed. */
public final class PaperLobbyDataBoard implements Listener,AutoCloseable {
    public static final String ID_KEY="lobby_stats_board",ORIGIN_KEY="lobby_stats_origin";
    public static final Set<String> IDS=Set.of("rating","rating-en","profile-lectern","profile-interaction");
    private static final long REFRESH=Duration.ofSeconds(30).toNanos();
    private final JavaPlugin plugin;private final LobbySettings settings;private final Consumer<Player> openProfile;
    private final NamespacedKey idKey,originKey;
    private final LobbyBoardRefresh<List<LobbyRankingRow>> refresh;
    private final Map<String,Entity> entities=new HashMap<>();
    private final Map<UUID,Long> clicks=new HashMap<>();
    private final LobbyDisplayAudience audience;
    private List<LobbyRankingRow> rows=List.of();
    private LobbyRankingModel.Status status=LobbyRankingModel.Status.LOADING;
    private World world;private Chunk ticket;private String origin;
    private boolean loading,ready;private volatile boolean closed;private long retryLoad;
    public PaperLobbyDataBoard(JavaPlugin plugin,LobbySettings settings,Supplier<? extends CompletionStage<List<LobbyRankingRow>>> source,Consumer<Player> openProfile){
        this.plugin=plugin;this.settings=settings;this.openProfile=openProfile;
        idKey=new NamespacedKey(plugin,ID_KEY);originKey=new NamespacedKey(plugin,ORIGIN_KEY);
        audience=new LobbyDisplayAudience(plugin,entity->entity instanceof TextDisplay&&owned(entity));
        refresh=new LobbyBoardRefresh<>(REFRESH,System::nanoTime,source,this::main,value->{rows=List.copyOf(value);status=LobbyRankingModel.Status.READY;render();},this::failure);
        if(settings.buildEnabled())plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public void tick(boolean available){
        audience.tick();
        if(closed||!settings.buildEnabled()||!available)return;
        if(!ready){if(!loading&&System.nanoTime()>=retryLoad)load();return;}
        refresh.tick(true);
        long now=System.nanoTime();clicks.values().removeIf(time->now-time>REFRESH);
    }
    private void load(){
        world=plugin.getServer().getWorld(settings.world());if(world==null)return;
        origin=world.getUID()+":"+settings.x()+":"+settings.y()+":"+settings.z();loading=true;
        world.getChunkAtAsync((settings.x()+26)>>4,(settings.z()+12)>>4,true).whenComplete((chunk,error)->main(()->{
            loading=false;if(error!=null){retryLoad=System.nanoTime()+REFRESH;failure(error);return;}
            try{PaperChunkTickets.acquire(plugin,world,chunk.getX(),chunk.getZ());ticket=chunk;render();ready=true;refresh.tick(true);}
            catch(RuntimeException failed){release();retryLoad=System.nanoTime()+REFRESH;failure(failed);}
        }));
    }
    private boolean owned(Entity entity){
        String id=entity.getPersistentDataContainer().get(idKey,PersistentDataType.STRING);
        return id!=null&&origin!=null&&origin.equals(entity.getPersistentDataContainer().get(originKey,PersistentDataType.STRING))&&IDS.contains(id);
    }
    private void render(){
        if(closed||world==null)return;
        entities.clear();
        for(Entity entity:world.getEntities())if((entity instanceof TextDisplay||entity instanceof BlockDisplay||entity instanceof Interaction)&&owned(entity)){
            String id=entity.getPersistentDataContainer().get(idKey,PersistentDataType.STRING);
            boolean correct=id.equals("rating")||id.equals("rating-en")?entity instanceof TextDisplay:id.equals("profile-lectern")?entity instanceof BlockDisplay:entity instanceof Interaction;
            if(!correct||entities.putIfAbsent(id,entity)!=null)entity.remove();
        }
        Location at=new Location(world,settings.x()+26.5,settings.y()+4.8,settings.z()+12.5);
        for(boolean chinese:List.of(true,false)){
            String id=chinese?"rating":"rating-en";
            TextDisplay text=(TextDisplay)entities.get(id);if(text==null){text=world.spawn(at,TextDisplay.class,display->display.setVisibleByDefault(false));tag(text,id);}
            text.teleport(at);text.text(LobbyRankingModel.render(rows,status,chinese?Locale.CHINESE:Locale.ENGLISH));text.setBillboard(Display.Billboard.CENTER);text.setLineWidth(420);
            text.setShadowed(true);text.setSeeThrough(false);text.setDefaultBackground(false);text.setBackgroundColor(Color.fromARGB(210,6,18,30));
            text.setBrightness(new Display.Brightness(15,15));text.setViewRange(1.5f);audience.track(text,chinese);
        }
        Location stand=new Location(world,settings.x()+26,settings.y()+1,settings.z()+12);
        BlockDisplay lectern=(BlockDisplay)entities.get("profile-lectern");if(lectern==null){lectern=world.spawn(stand,BlockDisplay.class);tag(lectern,"profile-lectern");}
        lectern.teleport(stand);var block=Material.LECTERN.createBlockData();((Directional)block).setFacing(BlockFace.WEST);lectern.setBlock(block);lectern.setBrightness(new Display.Brightness(15,15));
        Location hitbox=new Location(world,settings.x()+26.5,settings.y()+1,settings.z()+12.5);
        Interaction interaction=(Interaction)entities.get("profile-interaction");if(interaction==null){interaction=world.spawn(hitbox,Interaction.class);tag(interaction,"profile-interaction");}
        interaction.teleport(hitbox);interaction.setInteractionWidth(1.4f);interaction.setInteractionHeight(1.4f);interaction.setResponsive(true);
    }
    private void tag(Entity entity,String id){entity.setPersistent(true);entity.setInvulnerable(true);entity.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,id);entity.getPersistentDataContainer().set(originKey,PersistentDataType.STRING,origin);entities.put(id,entity);}
    private void failure(Throwable error){
        if(closed)return;status=rows.isEmpty()?LobbyRankingModel.Status.UNAVAILABLE:LobbyRankingModel.Status.STALE;
        plugin.getLogger().log(Level.WARNING,"大厅排行榜暂时无法更新，已保留最近结果",error);
        try{if(ticket!=null)render();}catch(RuntimeException presentation){plugin.getLogger().log(Level.WARNING,"大厅排行榜实体暂不可用",presentation);}
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void interact(PlayerInteractEntityEvent event){
        if(closed||!ready||event.getHand()!=EquipmentSlot.HAND||!owned(event.getRightClicked())||!(event.getRightClicked() instanceof Interaction))return;
        event.setCancelled(true);Player player=event.getPlayer();
        if(!player.getWorld().equals(world)||player.getLocation().distanceSquared(event.getRightClicked().getLocation())>36)return;
        long now=System.nanoTime();Long previous=clicks.put(player.getUniqueId(),now);if(previous!=null&&now-previous<300_000_000L)return;
        openProfile.accept(player);
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void interactAt(PlayerInteractAtEntityEvent event){interact(event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void damage(EntityDamageEvent event){if(owned(event.getEntity()))event.setCancelled(true);}
    public boolean ready(){return ready&&!closed;}
    public Set<UUID> entityIds(){return entities.values().stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    /** A disabled/moved lobby must not leave a visible board with an inactive profile entrance. */
    public void reconfigure(LobbySettings next){
        if(LobbyBoardPlacement.retire(settings,next)&&world!=null){
            world.getEntities().stream().filter(this::owned).filter(entity->entity instanceof TextDisplay||entity instanceof BlockDisplay||entity instanceof Interaction).toList().forEach(Entity::remove);
            entities.clear();
        }
        close();
    }
    private void main(Runnable action){if(closed||!plugin.isEnabled())return;if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,()->{if(!closed)action.run();});}
    private void release(){if(ticket!=null){PaperChunkTickets.release(plugin,ticket.getWorld(),ticket.getX(),ticket.getZ());ticket=null;}}
    /** Persistent entities are reconciled in place on restart; only owned live resources are released. */
    @Override public void close(){closed=true;refresh.close();audience.close();HandlerList.unregisterAll(this);release();clicks.clear();}
}
