package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.admin.PerformanceMetricsService;
import com.npucraft.battleroyale.config.ZoneUiSettings;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.zone.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Server;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import java.math.BigDecimal;
import java.util.*;

/** UUID-keyed per-viewer UI; no Player references or global world-border mutations. */
public final class PaperZoneUi {
    private static final Particle.DustOptions RED_EDGE=new Particle.DustOptions(Color.fromRGB(255,62,85),1.8f);
    private final Server server;
    private final ZoneUiSettings settings;
    private final Particle outlineParticle;
    private final Map<UUID,BossBar> bars=new HashMap<>();
    private final Set<UUID> navigationViewers=new HashSet<>();

    public PaperZoneUi(Server server,ZoneUiSettings settings) {
        this.server=server; this.settings=settings; this.outlineParticle=Particle.valueOf(settings.particle());
    }
    public void render(Player player,ZoneRuntime zone,long tick) {
        render(player,zone,tick,0,0,0,false);
    }
    public void render(Player player,ZoneRuntime zone,long tick,long alive,int kills,long teams,boolean spectator) {
        render(player,zone,tick,alive,kills,teams,spectator,null);
    }
    public void render(Player player,ZoneRuntime zone,long tick,long alive,int kills,long teams,boolean spectator,ZoneNavigation.Point airdrop) {
        var location=player.getLocation();
        if (settings.bossbarEnabled() && tick%settings.bossbarInterval()==0) {
            boolean outside=!zone.current().contains(location.getX(),location.getZ());
            BossBar bar=bars.computeIfAbsent(player.getUniqueId(),id -> {
                BossBar created=BossBar.bossBar(Component.empty(),1,BossBar.Color.BLUE,BossBar.Overlay.PROGRESS);
                player.showBossBar(created); return created;
            });
            bar.name(bossbarMessage(I18n.locale(player),zone,alive,kills,teams,spectator,location.getX(),location.getZ()))
                    .progress((float)zone.progress()).color(outside?BossBar.Color.RED:BossBar.Color.BLUE);
        }
        if (spectator || !settings.navigationEnabled()) clearNavigation(player);
        else if (tick%settings.navigationInterval()==0) {
            ZoneNavigation.Hint hint=ZoneNavigation.guide(zone.current(),zone.next(),location.getX(),location.getZ(),location.getYaw());
            player.sendActionBar(navigationMessage(I18n.locale(player),hint,zone.current(),zone.next(),airdrop,location.getX(),location.getZ(),location.getYaw()));
            navigationViewers.add(player.getUniqueId());
        }
        if (!spectator && settings.wallEnabled() && tick%settings.wallInterval()==0) {
            int sample=0;
            for (var point:ParticleWall.sample(zone.current(),location.getX(),location.getY(),location.getZ(),settings)) {
                // Colored curtain and bright outline share the existing cap; never emit a second layer per sample.
                if (settings.coloredWall() && sample++%5!=0)
                    player.spawnParticle(Particle.DUST,point.x(),point.y(),point.z(),1,0,0,0,0,RED_EDGE);
                else player.spawnParticle(outlineParticle,point.x(),point.y(),point.z(),1,0,0,0,0);
                PerformanceMetricsService.LIVE.add(PerformanceMetricsService.Counter.PARTICLE_SAMPLES,1);
            }
        }
    }

    /** Literal text, kept separate from rendering for tests and server-side probes. */
    public static Component navigationMessage(ZoneNavigation.Hint hint,Zone current,Zone next) {
        return navigationMessage(hint,current,next,null,0,0,0);
    }
    public static Component navigationMessage(ZoneNavigation.Hint hint,Zone current,Zone next,ZoneNavigation.Point airdrop,double x,double z,double yaw) {
        return navigationMessage(Locale.SIMPLIFIED_CHINESE,hint,current,next,airdrop,x,z,yaw);
    }
    public static Component navigationMessage(Locale locale,ZoneNavigation.Hint hint,Zone current,Zone next,ZoneNavigation.Point airdrop,double x,double z,double yaw) {
        Component action;
        if(current.halfSize()==0){
            action=Component.text(I18n.text(locale,"安全区已消失","No safe zone remains"),UiText.ERROR)
                    .decoration(TextDecoration.BOLD,true).decoration(TextDecoration.ITALIC,false);
        }else{
            String target=next!=null&&next.halfSize()==0?I18n.text(locale,"最终收拢点","Final collapse point"):
                    I18n.text(locale,next==null?"安全区":"下圈",next==null?"Safe zone ":"Next zone ")+I18n.text(locale,hint.outside()?"边界":"中心",hint.outside()?"edge":"center");
            action=Component.text(hint.arrow()+" "+target,hint.outside()?UiText.ERROR:UiText.SUCCESS)
                    .decoration(TextDecoration.BOLD,hint.outside()).decoration(TextDecoration.ITALIC,false)
                    .append(UiText.value(I18n.text(locale," %s 米"," %s m",(long)Math.ceil(hint.distance()))));
        }
        if(airdrop!=null){
            double dx=airdrop.x()-x,dz=airdrop.z()-z;
            action=action.append(UiText.muted(" · ")).append(UiText.value(I18n.text(locale,"%s 空投 %s 米","%s Airdrop %s m",ZoneNavigation.arrow(dx,dz,yaw),(long)Math.ceil(Math.hypot(dx,dz)))));
        }
        return action;
    }
    public static Component bossbarMessage(ZoneRuntime zone,long alive,int kills,long teams,boolean spectator,double x,double z) {
        return bossbarMessage(Locale.SIMPLIFIED_CHINESE,zone,alive,kills,teams,spectator,x,z);
    }
    public static Component bossbarMessage(Locale locale,ZoneRuntime zone,long alive,int kills,long teams,boolean spectator,double x,double z) {
        Zone destination=zone.next()==null?zone.current():zone.next();
        String area=BigDecimal.valueOf(destination.halfSize()).multiply(BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString()+"²";
        String text=I18n.text(locale,"存活：%s","Alive: %s",alive)+(spectator?I18n.text(locale," | 队伍：%s"," | Teams: %s",teams):I18n.text(locale," | 击杀：%s"," | Kills: %s",kills))
                +I18n.text(locale," | 第 %s/%s 阶段 | %s面积 %s ㎡"," | Stage %s/%s | %sarea %s m²",zone.stageNumber(),zone.stageCount(),I18n.text(locale,zone.next()==null?"安全区":"下圈",zone.next()==null?"Safe zone ":"Next zone "),area)
                +" | "+I18n.state(locale,zone.phase())+" | "
                +(zone.phase()==ZonePhase.FINAL?I18n.text(locale,"最终阶段","Final stage"):I18n.text(locale,"%s 秒","%s s",(long)Math.ceil(zone.remainingSeconds())))
                +" | "+(spectator?I18n.text(locale,"观战中","Spectating"):zone.current().contains(x,z)?I18n.text(locale,"安全区内","Inside zone"):I18n.text(locale,"安全区外","Outside zone"));
        return UiText.text(text);
    }
    private void clearNavigation(Player player) {
        if (navigationViewers.remove(player.getUniqueId())) player.sendActionBar(Component.empty());
    }
    public int size(){return bars.size();}
    public void detach(UUID id) {
        BossBar bar=bars.remove(id);
        boolean navigation=navigationViewers.remove(id);
        Player player=server.getPlayer(id);
        if (player==null) return;
        if (bar!=null) player.hideBossBar(bar);
        if (navigation) player.sendActionBar(Component.empty());
    }
    public void close() {
        Set<UUID> viewers=new HashSet<>(bars.keySet()); viewers.addAll(navigationViewers);
        for (UUID id:viewers) detach(id);
    }
}
