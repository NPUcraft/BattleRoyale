package com.lastsector.paper;
import com.lastsector.config.ZoneUiSettings;
import com.lastsector.zone.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import java.util.*;
/** UUID-keyed per-viewer bars, no retained Player references. */
public final class PaperZoneUi {
    private final Server server;
    private final ZoneUiSettings settings;
    private final Map<UUID,BossBar> bars=new HashMap<>();
    public PaperZoneUi(Server server,ZoneUiSettings settings) { this.server=server; this.settings=settings; }
    public void render(Player player,ZoneRuntime zone,long tick) {
        render(player,zone,tick,0,0,0,false);
    }
    public void render(Player player,ZoneRuntime zone,long tick,long alive,int kills,long teams,boolean spectator) {
        var location=player.getLocation();
        if (settings.bossbarEnabled() && tick%settings.bossbarInterval()==0) {
            double distance=zone.current().distanceOutside(location.getX(),location.getZ());
            String text="Alive: "+alive+(spectator?" | Teams: "+teams:" | Kills: "+kills)+" | Zone "+(zone.stageIndex()+1)+" | "+zone.phase()+" | "+
                    (zone.phase()==ZonePhase.FINAL?"Final":(long)Math.ceil(zone.remainingSeconds())+"s")+" | "+
                    (spectator?"Spectating":distance==0?"Inside":String.format(Locale.ROOT,"%.1fm outside",distance));
            BossBar bar=bars.computeIfAbsent(player.getUniqueId(),id -> {
                BossBar created=BossBar.bossBar(Component.empty(),1,BossBar.Color.BLUE,BossBar.Overlay.PROGRESS);
                player.showBossBar(created); return created;
            });
            bar.name(Component.text(text)).progress((float)zone.progress()).color(distance>0?BossBar.Color.RED:BossBar.Color.BLUE);
        }
        if (!spectator && settings.wallEnabled() && tick%settings.wallInterval()==0)
            for(var point:ParticleWall.sample(zone.current(),location.getX(),location.getY(),location.getZ(),settings))
                {player.spawnParticle(Particle.valueOf(settings.particle()),point.x(),point.y(),point.z(),1,0,0,0,0);com.lastsector.admin.PerformanceMetricsService.LIVE.add(com.lastsector.admin.PerformanceMetricsService.Counter.PARTICLE_SAMPLES,1);}
    }
    public int size(){return bars.size();}
    public void detach(UUID id) {
        BossBar bar=bars.remove(id);
        Player player=server.getPlayer(id);
        if (bar!=null && player!=null) player.hideBossBar(bar);
    }
    public void close() { for(UUID id:List.copyOf(bars.keySet())) detach(id); }
}

