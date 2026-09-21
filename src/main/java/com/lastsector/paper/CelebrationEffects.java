package com.lastsector.paper;
import com.lastsector.session.*;
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
    private final JavaPlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID,Set<UUID>> entities=new HashMap<>();
    public CelebrationEffects(JavaPlugin plugin) {this.plugin=plugin;marker=new NamespacedKey(plugin,"celebration_session");}
    public void title(GameSession session,Function<UUID,String> names) {
        var result=session.outcome().orElseThrow();
        String winners=result.winnerIds().stream().sorted().map(names).collect(java.util.stream.Collectors.joining(" & "));
        Title title=Title.title(Component.text(result.tie()?"TIE":result.winnerIds().isEmpty()?"MATCH ENDED":"WINNER"),Component.text(winners),
                Title.Times.times(Duration.ofMillis(300),Duration.ofSeconds(5),Duration.ofMillis(500)));
        for(UUID id:session.players().keySet()) {Player p=plugin.getServer().getPlayer(id);if(p!=null) p.showTitle(title);}
    }
    public void fire(GameSession session) {
        World world=plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()); if(world==null) return;
        Set<UUID> ids=entities.computeIfAbsent(session.sessionId(),id->new HashSet<>());ids.removeIf(id->plugin.getServer().getEntity(id)==null);
        for(UUID winner:session.outcome().orElseThrow().winnerIds()) {
            Player p=plugin.getServer().getPlayer(winner);
            Location at=p!=null && p.getWorld().getUID().equals(world.getUID())?p.getLocation().add(0,2,0):world.getSpawnLocation().add(0,2,0);
            Firework firework=world.spawn(at,Firework.class,entity->{
                entity.getPersistentDataContainer().set(marker,PersistentDataType.STRING,session.sessionId().toString());
                var meta=entity.getFireworkMeta();meta.setPower(0);meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(Color.WHITE,Color.YELLOW).trail(true).build());
                entity.setFireworkMeta(meta);entity.setInvulnerable(true);
            });ids.add(firework.getUniqueId());
        }
    }
    public boolean marked(Entity entity) {
        String session=entity.getPersistentDataContainer().get(marker,PersistentDataType.STRING);if(session==null) return false;
        try {return entities.getOrDefault(UUID.fromString(session),Set.of()).contains(entity.getUniqueId());} catch(IllegalArgumentException invalid) {return false;}
    }
    public void close(UUID session) {for(UUID id:entities.getOrDefault(session,Set.of())) {Entity e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}entities.remove(session);}
}
