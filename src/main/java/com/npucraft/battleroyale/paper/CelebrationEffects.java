package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** Neutral built-in effects only, with explicit registry + PDC identity for damage protection. */
public final class CelebrationEffects {
    public static final int WAVES=3,WAVE_DELAY_TICKS=8,MAX_ROCKETS_PER_WAVE=8,MAX_LIVE_PER_SESSION=32;
    private final JavaPlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID,Set<UUID>> entities=new HashMap<>();
    private final Map<UUID,Burst> bursts=new HashMap<>();
    private static final class Burst {final List<org.bukkit.scheduler.BukkitTask> tasks=new ArrayList<>();}
    public CelebrationEffects(JavaPlugin plugin) {this.plugin=plugin;marker=new NamespacedKey(plugin,"celebration_session");}
    public void title(GameSession session,Function<UUID,String> names) {
        var result=session.outcome().orElseThrow();
        String winners=result.winnerIds().stream().sorted().map(names).collect(java.util.stream.Collectors.joining(" & "));
        for(Player player:audience(session)){
            Component headline=result.tie()?UiText.warning(player,"平局","Draw"):result.winnerIds().isEmpty()?UiText.heading(player,"比赛结束","Match over"):UiText.success(player,"胜利","Victory");
            player.showTitle(Title.title(headline.decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),UiText.value(winners),Title.Times.times(Duration.ofMillis(300),Duration.ofSeconds(5),Duration.ofMillis(500))));
            player.sendMessage(UiText.message(player,"比赛已结束，倒计时结束后自动返回大厅。使用 /br leave 可提前返回；本局结算结束后可加入下一局。","The match is over. You will return to the lobby after the countdown. Use /br leave to return early; you can join another match once this one finishes."));
        }
    }
    public void returnCountdown(GameSession session,int seconds){for(Player player:audience(session))player.sendActionBar(returnStatus(I18n.locale(player),seconds));}
    public static Component returnStatus(int seconds){return returnStatus(Locale.SIMPLIFIED_CHINESE,seconds);}
    public static Component returnStatus(Locale locale,int seconds){return seconds<=0?UiText.success(I18n.text(locale,"结算结束，正在返回大厅……","Returning to the lobby...")):UiText.text(I18n.text(locale,"返回大厅倒计时：%s 秒  |  /br leave 提前返回","Lobby return: %s s | /br leave to return now",seconds));}
    /** A player who already restored their lobby snapshot must not receive match UI there. */
    private List<Player> audience(GameSession session){
        World world=session.gameWorld().map(game->plugin.getServer().getWorld(game.worldName())).orElse(null);
        return world==null?List.of():world.getPlayers();
    }
    public void detach(UUID playerId){Player player=plugin.getServer().getPlayer(playerId);if(player!=null){player.clearTitle();player.sendActionBar(Component.empty());}}
    public void fire(GameSession session) {fire(session,id->org.bukkit.Color.YELLOW);}
    public void fire(GameSession session,Function<UUID,Color> colors) {
        if(session.state()!=GameState.ENDING||bursts.containsKey(session.sessionId())||session.outcome().orElseThrow().winnerIds().isEmpty())return;
        var burst=new Burst();bursts.put(session.sessionId(),burst);
        try{
            wave(session,colors,0);
            for(int wave=1;wave<WAVES;wave++){int index=wave;burst.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin,()->{
                if(bursts.get(session.sessionId())!=burst)return;
                try{if(session.state()==GameState.ENDING)wave(session,colors,index);}
                catch(RuntimeException failure){close(session.sessionId());plugin.getLogger().log(java.util.logging.Level.WARNING,"胜利烟花已停止",failure);}
                finally{if(index==WAVES-1)bursts.remove(session.sessionId(),burst);}
            },(long)wave*WAVE_DELAY_TICKS));}
        }catch(RuntimeException failure){close(session.sessionId());throw failure;}
    }
    public static int rocketsPerWave(int winners){return (int)Math.min(MAX_ROCKETS_PER_WAVE,Math.max(0,(long)winners)*2);}
    private void wave(GameSession session,Function<UUID,Color> colors,int wave){
        World world=plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName());if(world==null)return;
        var winners=session.outcome().orElseThrow().winnerIds().stream().sorted().toList();if(winners.isEmpty())return;
        Set<UUID> ids=entities.computeIfAbsent(session.sessionId(),id->new HashSet<>());ids.removeIf(id->plugin.getServer().getEntity(id)==null);
        int count=Math.min(rocketsPerWave(winners.size()),MAX_LIVE_PER_SESSION-ids.size());
        for(int i=0;i<count;i++){
            UUID winner=winners.get((i/2+wave*4)%winners.size());Player player=plugin.getServer().getPlayer(winner);
            Location at=player!=null&&player.getWorld().equals(world)?player.getLocation().add(0,2,0):world.getSpawnLocation().add(0,2,0);
            double angle=(i%2*Math.PI)+(wave*Math.PI/3);at.add(Math.cos(angle)*3,Math.min(wave,2),Math.sin(angle)*3);
            Color primary=Objects.requireNonNullElse(colors.apply(winner),Color.YELLOW);
            Firework firework=world.spawn(at,Firework.class,entity->{
                RecoveryEntityCleaner.mark(entity);entity.setPersistent(false);entity.getPersistentDataContainer().set(marker,PersistentDataType.STRING,session.sessionId().toString());
                var meta=entity.getFireworkMeta();meta.setPower(1);
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(primary,Color.WHITE,Color.AQUA).withFade(Color.ORANGE,Color.FUCHSIA).trail(true).flicker(true).build());
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST).withColor(Color.YELLOW,primary).withFade(Color.WHITE).trail(true).build());
                entity.setFireworkMeta(meta);entity.setInvulnerable(true);
            });ids.add(firework.getUniqueId());
        }
    }
    public boolean marked(Entity entity) {
        String session=entity.getPersistentDataContainer().get(marker,PersistentDataType.STRING);if(session==null) return false;
        try {return entities.getOrDefault(UUID.fromString(session),Set.of()).contains(entity.getUniqueId());} catch(IllegalArgumentException invalid) {return false;}
    }
    public void close(UUID session) {var burst=bursts.remove(session);if(burst!=null)burst.tasks.forEach(org.bukkit.scheduler.BukkitTask::cancel);for(UUID id:entities.getOrDefault(session,Set.of())) {Entity e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}entities.remove(session);}
}
