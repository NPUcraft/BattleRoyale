package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.LobbySettings;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.service.I18n;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.zip.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** A bounded, backed-up, once-only lobby build. All world access stays on the server thread. */
public final class PaperLobbyStructure implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PluginRuntime runtime;
    private final LobbySettings settings;
    private final World world;
    private final String worldIdentity;
    private final BiConsumer<Player,String> choose;
    private final LobbyDisplayAudience audience;
    private static final Set<String> LABEL_IDS=Set.of("solo","duo","squad","welcome","solo-en","duo-en","squad-en","welcome-en");
    private final List<LobbyBlueprint.Block> plan=LobbyBlueprint.blocks();
    private final List<Chunk> tickets=new ArrayList<>();
    private final Map<LobbyBlueprint.Position,String> original=new LinkedHashMap<>();
    private final Path marker,backup;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"BattleRoyale-lobby-io"));
    private BukkitTask task;
    private boolean closed,ready,failed,resuming;
    private int cursor;
    private Stage stage=Stage.LOADING;
    private enum Stage { LOADING,CHECKING,SAVING,BUILDING,READY,FAILED }

    public PaperLobbyStructure(JavaPlugin plugin,PluginRuntime runtime,LobbySettings settings,
            String configuredLobby,BiConsumer<Player,String> choose) {
        this.plugin=plugin;this.runtime=runtime;this.settings=settings;this.choose=choose;
        marker=plugin.getDataFolder().toPath().resolve("lobby-structure.properties");
        backup=plugin.getDataFolder().toPath().resolve("lobby-structure-original.blocks.gz");
        world=plugin.getServer().getWorld(settings.world());
        worldIdentity=world==null?"":world.getUID().toString();
        audience=new LobbyDisplayAudience(plugin,this::ownedLabel);
        if(!settings.buildEnabled()) {ready=true;stage=Stage.READY;return;}
        if(!settings.world().equals(configuredLobby)||world==null) {
            fail(new IllegalStateException("lobby.yml 的 structure.world 必须与 config.yml 的 lobby.world 一致且已经加载；未修改任何方块"));return;
        }
        if(settings.y()-3<world.getMinHeight()||settings.y()+10>=world.getMaxHeight()) {
            fail(new IllegalStateException("大厅建筑超出该世界高度范围"));return;
        }
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        CompletableFuture.supplyAsync(()->{
            try {
                AtomicFiles.safe(marker);AtomicFiles.safe(backup);
                var saved=new Properties();
                if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) {
                    if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS))throw new IOException("大厅标记不是普通文件");
                    try(var input=Files.newInputStream(marker)){saved.load(input);}
                    verifyIdentity(saved);
                    String state=saved.getProperty("state");
                    if(!Set.of("BUILDING","READY").contains(state))throw new IOException("大厅标记状态无效");
                    if(state.equals("BUILDING"))readBackup();
                } else if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("存在无标记的大厅备份，请管理员核查后再施工");
                }
                return saved;
            }catch(IOException error){throw new CompletionException(error);}
        },io).whenComplete((saved,error)->main(()->{
            if(error!=null){fail(error);return;}
            boolean completed="READY".equals(saved.getProperty("state"));
            resuming="BUILDING".equals(saved.getProperty("state"));
            loadChunks(completed);
        }));
    }
    private Properties identity(String state) {
        var values=new Properties();values.setProperty("world",settings.world());values.setProperty("world-uuid",worldIdentity);
        values.setProperty("x",Integer.toString(settings.x()));values.setProperty("y",Integer.toString(settings.y()));values.setProperty("z",Integer.toString(settings.z()));
        values.setProperty("radius",Integer.toString(settings.radius()));values.setProperty("blueprint",Integer.toString(LobbyBlueprint.VERSION));values.setProperty("state",state);return values;
    }
    private void verifyIdentity(Properties saved)throws IOException {
        for(var entry:identity("BUILDING").entrySet())if(!entry.getKey().equals("state")&&!Objects.equals(saved.get(entry.getKey()),entry.getValue()))
            throw new IOException("大厅建造记录与当前世界或坐标不符（"+entry.getKey()+"），已保留已有建筑；请先核查备份与配置");
    }
    private void readBackup()throws IOException {
        if(!Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS))throw new IOException("施工恢复所需的原始方块备份缺失");
        try(var input=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(backup)),StandardCharsets.UTF_8))) {
            String line;int count=0;
            while((line=input.readLine())!=null) {
                if(++count>plan.size())throw new IOException("大厅原始方块备份超出边界");
                String[] parts=line.split("\\|",4);if(parts.length!=4)throw new IOException("大厅原始方块备份格式无效");
                var position=new LobbyBlueprint.Position(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
                if(!settings.within(settings.x()+position.x(),settings.y()+position.y(),settings.z()+position.z()))throw new IOException("大厅原始方块备份坐标越界");
                original.put(position,parts[3]);
            }
        }catch(NumberFormatException error){throw new IOException("大厅原始方块备份坐标无效",error);}
    }
    private void loadChunks(boolean completed) {
        var pending=new ArrayList<CompletableFuture<Chunk>>();
        for(int x=(settings.x()-32)>>4;x<=(settings.x()+32)>>4;x++)for(int z=(settings.z()-32)>>4;z<=(settings.z()+32)>>4;z++)
            pending.add(world.getChunkAtAsync(x,z,true).thenApply(chunk->{if(!closed){chunk.addPluginChunkTicket(plugin);tickets.add(chunk);}return chunk;}));
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((unused,error)->main(()->{
            if(error!=null){fail(error);return;}
            if(completed) {activate(false);return;}
            stage=Stage.CHECKING;
            task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1,1);
        }));
    }
    private org.bukkit.block.Block at(LobbyBlueprint.Position position) {return world.getBlockAt(settings.x()+position.x(),settings.y()+position.y(),settings.z()+position.z());}
    private void tick() {
        if(closed||failed)return;
        try {
            if(stage==Stage.CHECKING) {
                for(int n=0;n<settings.blocksPerTick()&&cursor<plan.size();n++,cursor++) {
                    var target=plan.get(cursor);var block=at(target.position());String current=block.getBlockData().getAsString();
                    if(resuming) {
                        if(!block.getType().isAir()&&block.getType()!=target.material()&&!current.equals(original.get(target.position())))
                            throw new IllegalStateException("中断施工区域发现未记录的改动，已停止自动恢复："+block.getLocation());
                    } else {
                        if(!block.getType().isAir() && block.getState() instanceof TileState)
                            throw new IllegalStateException("施工区域存在容器或带数据的方块，请先移动后重新启动："+block.getLocation());
                        if(!settings.clearExisting()&&!block.getType().isAir())
                            throw new IllegalStateException("施工区域不是空气；明确同意后可设置 structure.clear-existing-blocks=true，或提高 center.y");
                        if(!block.getType().isAir())original.put(target.position(),current);
                    }
                }
                if(cursor==plan.size()) {
                    stage=Stage.SAVING;cursor=0;
                    CompletableFuture.runAsync(()->{try{if(!resuming)writeBackup();writeMarker("BUILDING");}catch(IOException error){throw new CompletionException(error);}},io)
                        .whenComplete((unused,error)->main(()->{if(error!=null)fail(error);else stage=Stage.BUILDING;}));
                }
            }else if(stage==Stage.BUILDING) {
                for(int n=0;n<settings.blocksPerTick()&&cursor<plan.size();n++,cursor++) {
                    var target=plan.get(cursor);var block=at(target.position());
                    if(block.getType()!=target.material())block.setType(target.material(),false);
                    if(block.getBlockData() instanceof Leaves leaves&&!leaves.isPersistent()){leaves.setPersistent(true);block.setBlockData(leaves,false);}
                }
                if(cursor==plan.size()) {
                    stage=Stage.SAVING;labels();setSpawn();world.save();
                    CompletableFuture.runAsync(()->{try{writeMarker("READY");}catch(IOException error){throw new CompletionException(error);}},io)
                        .whenComplete((unused,error)->main(()->{if(error!=null)fail(error);else activate(true);}));
                }
            }
        }catch(RuntimeException error){fail(error);}
    }
    private void writeBackup()throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var output=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(bytes),StandardCharsets.UTF_8))) {
            for(var entry:original.entrySet()){var p=entry.getKey();output.write(p.x()+"|"+p.y()+"|"+p.z()+"|"+entry.getValue());output.newLine();}
        }
        AtomicFiles.write(backup,bytes.toByteArray());
    }
    private void writeMarker(String state)throws IOException {
        var bytes=new ByteArrayOutputStream();identity(state).store(bytes,"BattleRoyale owned lobby build; retain this marker and the original-block backup");AtomicFiles.write(marker,bytes.toByteArray());
    }
    private void setSpawn() {world.setSpawnLocation(settings.x(),settings.y()+1,settings.z(),180f);}
    private void activate(boolean built) {
        if(!world.getBlockAt(settings.x(),settings.y(),settings.z()).getType().isSolid()
                ||!world.getBlockAt(settings.x(),settings.y()+1,settings.z()).isPassable()
                ||!world.getBlockAt(settings.x(),settings.y()+2,settings.z()).isPassable()) {
            fail(new IllegalStateException("大厅中心出生空间已被修改，请管理员修复；不会自动覆盖已有建筑"));return;
        }
        // READY protects the blocks, not the presentation of our own persistent labels.
        // Refresh existing entities in place so upgrades apply without rebuilding the plaza.
        if(!built)labels();
        setSpawn();ready=true;stage=Stage.READY;if(task!=null)task.cancel();releaseTickets();
        plugin.getLogger().info("LOBBY_READY world="+world.getName()+" center="+settings.x()+","+settings.y()+","+settings.z()+" built="+built);
    }
    private void labels() {
        var key=new NamespacedKey(plugin,"lobby_structure_label");
        var existing=new HashMap<String,TextDisplay>();
        var managed=LABEL_IDS;
        for(var chunk:tickets)for(var entity:chunk.getEntities())if(entity instanceof TextDisplay display) {
            String id=display.getPersistentDataContainer().get(key,PersistentDataType.STRING);
            if(id!=null&&managed.contains(id)) {
                if(existing.putIfAbsent(id,display)!=null)display.remove();
            }
        }
        for(boolean chinese:List.of(true,false)){
        Locale locale=chinese?Locale.CHINESE:Locale.ENGLISH;String suffix=chinese?"":"-en";
        label(key,existing,"solo"+suffix,-18,4.1,-12,roomLabel(I18n.text(locale,"单人竞技","Solo"),I18n.text(locale,"独自出发 · 最后生还","Go solo · Be the last survivor"),NamedTextColor.AQUA,locale),chinese);
        label(key,existing,"duo"+suffix,0,4.1,-18,roomLabel(I18n.text(locale,"双人组队","Duo"),I18n.text(locale,"并肩作战 · 互相掩护","Fight together · Watch each other's back"),NamedTextColor.GREEN,locale),chinese);
        label(key,existing,"squad"+suffix,18,4.1,-12,roomLabel(I18n.text(locale,"四人小队","Squad"),I18n.text(locale,"集结队友 · 决战终圈","Rally your squad · Reach the final zone"),NamedTextColor.LIGHT_PURPLE,locale),chinese);
        label(key,existing,"welcome"+suffix,0,4.3,7,Component.empty()
                .append(Component.text("NPUcraft",NamedTextColor.AQUA,TextDecoration.BOLD))
                .append(Component.text(" · ",NamedTextColor.DARK_GRAY))
                .append(Component.text(I18n.text(locale,"大逃杀","BattleRoyale"),NamedTextColor.GOLD,TextDecoration.BOLD))
                .appendNewline().append(Component.text(I18n.text(locale,"欢迎来到空中大厅","Welcome to the sky lobby"),NamedTextColor.WHITE))
                .appendNewline().append(Component.text(I18n.text(locale,"右键讲台或手持指南针选择房间","Right-click a lectern or use your compass"),NamedTextColor.YELLOW))
                .appendNewline().append(Component.text(I18n.text(locale,"物资搜寻 · 安全区收缩 · 生存到最后","Find loot · Follow the zone · Survive"),NamedTextColor.GRAY)),chinese);
        }
    }
    private boolean ownedLabel(Entity entity){
        String id=entity.getPersistentDataContainer().get(new NamespacedKey(plugin,"lobby_structure_label"),PersistentDataType.STRING);
        return settings.buildEnabled()&&entity instanceof TextDisplay&&world!=null&&world.equals(entity.getWorld())&&id!=null&&LABEL_IDS.contains(id)
                &&Math.abs(entity.getLocation().getX()-settings.x())<=36&&Math.abs(entity.getLocation().getZ()-settings.z())<=36;
    }
    private static Component roomLabel(String title,String subtitle,NamedTextColor accent,Locale locale) {
        return Component.text(title,accent,TextDecoration.BOLD)
                .appendNewline().append(Component.text(subtitle,NamedTextColor.GRAY).decoration(TextDecoration.BOLD,false))
                .appendNewline().append(Component.text(I18n.text(locale,"右键下方讲台加入","Right-click the lectern to join"),NamedTextColor.GOLD).decoration(TextDecoration.BOLD,false));
    }
    private void label(NamespacedKey key,Map<String,TextDisplay> existing,String id,double x,double y,double z,Component text,boolean chinese) {
        var display=existing.get(id);
        if(display==null)display=world.spawn(new Location(world,settings.x()+x+.5,settings.y()+y,settings.z()+z+.5),TextDisplay.class,value->value.setVisibleByDefault(false));
        display.text(text.decoration(TextDecoration.ITALIC,false));
        display.setBillboard(Display.Billboard.CENTER);display.setShadowed(true);display.setSeeThrough(false);
        display.setDefaultBackground(false);display.setBackgroundColor(Color.fromARGB(190,6,18,30));
        display.setBrightness(new Display.Brightness(15,15));display.setLineWidth(250);display.setViewRange(1.3f);display.setPersistent(true);
        display.getPersistentDataContainer().set(key,PersistentDataType.STRING,id);
        audience.track(display,chinese);
    }
    public void viewers(){audience.tick();}
    public boolean ready(){return ready;}
    public boolean busy(){return settings.buildEnabled()&&!ready&&!failed;}
    public boolean failed(){return failed;}
    public Location spawn(){return world==null?null:new Location(world,settings.x()+.5,settings.y()+1,settings.z()+.5,180,0);}
    private void main(Runnable action){if(!closed&&plugin.isEnabled())plugin.getServer().getScheduler().runTask(plugin,()->{if(!closed)action.run();});}
    private void fail(Throwable error){failed=true;stage=Stage.FAILED;if(task!=null)task.cancel();releaseTickets();plugin.getLogger().log(java.util.logging.Level.SEVERE,"大厅建造已停止；现有建筑、标记与备份均已保留",error);}
    private void releaseTickets(){for(var chunk:tickets)chunk.removePluginChunkTicket(plugin);tickets.clear();}
    private boolean region(Location location){return settings.buildEnabled()&&settings.protection()&&world!=null&&world.equals(location.getWorld())&&Math.abs(location.getX()-settings.x())<=36&&Math.abs(location.getZ()-settings.z())<=36;}
    private boolean blockRegion(Location location){return region(location)&&location.getBlockY()>=settings.y()-4&&location.getBlockY()<=settings.y()+11;}
    private boolean bypass(Player player){return settings.adminBuild()&&player.hasPermission("battleroyale.admin");}
    private boolean visitor(Player player){
        var id=player.getUniqueId();
        if(runtime.editing(id)||!runtime.recoveryReady()||runtime.pendingRestore(id)||runtime.spectators().registry().find(id).isPresent())return false;
        var session=runtime.rooms().participant(id).orElse(null);
        return session==null||session.state()==com.npucraft.battleroyale.session.GameState.WAITING||session.state()==com.npucraft.battleroyale.session.GameState.COUNTDOWN||session.players().get(id).state()==com.npucraft.battleroyale.player.PlayerState.ELIMINATED;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void breakBlock(BlockBreakEvent event){if(blockRegion(event.getBlock().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void place(BlockPlaceEvent event){if(blockRegion(event.getBlock().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void bucketEmpty(PlayerBucketEmptyEvent event){if(blockRegion(event.getBlock().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void bucketFill(PlayerBucketFillEvent event){if(blockRegion(event.getBlock().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void drop(PlayerDropItemEvent event){if(region(event.getPlayer().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void pickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player player&&region(player.getLocation())&&!bypass(player))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void damage(EntityDamageEvent event){if(event.getEntity() instanceof Player player&&region(player.getLocation())&&visitor(player)){event.setCancelled(true);player.setFireTicks(0);player.setFallDistance(0);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hunger(FoodLevelChangeEvent event){if(event.getEntity() instanceof Player player&&region(player.getLocation())&&visitor(player)){event.setCancelled(true);player.setFoodLevel(20);player.setSaturation(20);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void move(PlayerMoveEvent event){
        var player=event.getPlayer();var to=event.getTo();if(!ready||!settings.protection()||world==null||!world.equals(to.getWorld())||!visitor(player)||bypass(player))return;
        if(to.getY()<settings.y()-4||Math.abs(to.getX()-settings.x())>35||Math.abs(to.getZ()-settings.z())>35){event.setTo(spawn());player.setFallDistance(0);}
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void interact(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||event.getClickedBlock()==null||!blockRegion(event.getClickedBlock().getLocation()))return;
        // The hotbar action has already been dispatched by PaperLobby; do not also join a room.
        if(event.getItem()!=null&&event.getItem().hasItemMeta()&&event.getItem().getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin,"lobby_action"),PersistentDataType.STRING))return;
        var player=event.getPlayer();if(!bypass(player))event.setCancelled(true);
        if(!ready||!event.getAction().isRightClick()||!visitor(player))return;
        var block=event.getClickedBlock();if(block.getType()!=Material.LECTERN||block.getY()!=settings.y()+2)return;
        int x=block.getX()-settings.x(),z=block.getZ()-settings.z();
        String action=x==-18&&z==-13?"solo":x==0&&z==-19?"duo":x==18&&z==-13?"squad":x==0&&z==7?"rooms":null;
        if(action!=null){event.setCancelled(true);choose.accept(player,action);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void interactEntity(PlayerInteractEntityEvent event){if(region(event.getRightClicked().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void interactEntityAt(PlayerInteractAtEntityEvent event){if(region(event.getRightClicked().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void armorStand(PlayerArmorStandManipulateEvent event){if(region(event.getRightClicked().getLocation())&&!bypass(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void placeEntity(EntityPlaceEvent event){if(region(event.getEntity().getLocation())&&(event.getPlayer()==null||!bypass(event.getPlayer())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hanging(org.bukkit.event.hanging.HangingBreakEvent event){
        if(region(event.getEntity().getLocation())&&!(event instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent by&&by.getRemover() instanceof Player player&&bypass(player)))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hangingByEntity(org.bukkit.event.hanging.HangingBreakByEntityEvent event){hanging(event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void attack(EntityDamageByEntityEvent event){
        if(region(event.getEntity().getLocation())&&!(event.getDamager() instanceof Player player&&bypass(player)))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void launch(ProjectileLaunchEvent event){if(event.getEntity().getShooter() instanceof Player player&&region(player.getLocation())&&!bypass(player))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void potion(PotionSplashEvent event){for(var entity:event.getAffectedEntities())if(entity instanceof Player player&&region(player.getLocation())&&visitor(player))event.setIntensity(player,0);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void cloud(AreaEffectCloudApplyEvent event){event.getAffectedEntities().removeIf(entity->entity instanceof Player player&&region(player.getLocation())&&visitor(player));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void vehicleDamage(org.bukkit.event.vehicle.VehicleDamageEvent event){if(region(event.getVehicle().getLocation())&&!(event.getAttacker() instanceof Player player&&bypass(player)))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void vehicleDestroy(org.bukkit.event.vehicle.VehicleDestroyEvent event){if(region(event.getVehicle().getLocation())&&!(event.getAttacker() instanceof Player player&&bypass(player)))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void entityBlock(EntityChangeBlockEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void explode(EntityExplodeEvent event){event.blockList().removeIf(block->blockRegion(block.getLocation()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void blockExplode(BlockExplodeEvent event){event.blockList().removeIf(block->blockRegion(block.getLocation()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void burn(BlockBurnEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void ignite(BlockIgniteEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void leaves(LeavesDecayEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void flow(BlockFromToEvent event){if(blockRegion(event.getToBlock().getLocation())||blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void form(BlockFormEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void grow(BlockGrowEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void spread(BlockSpreadEvent event){if(blockRegion(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void piston(BlockPistonExtendEvent event){if(blockRegion(event.getBlock().getLocation())||event.getBlocks().stream().anyMatch(block->blockRegion(block.getLocation())||blockRegion(block.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void retract(BlockPistonRetractEvent event){if(blockRegion(event.getBlock().getLocation())||event.getBlocks().stream().anyMatch(block->blockRegion(block.getLocation())||blockRegion(block.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void spawnMob(CreatureSpawnEvent event){if(region(event.getLocation())&&event.getSpawnReason()!=CreatureSpawnEvent.SpawnReason.CUSTOM)event.setCancelled(true);}
    @Override public void close(){closed=true;if(task!=null)task.cancel();audience.close();HandlerList.unregisterAll(this);releaseTickets();io.shutdown();}
}
