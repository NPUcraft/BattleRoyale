package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.zone.GameClock;
import java.util.*;
import java.util.function.Supplier;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Server;

/** Session-owned stage/count progress; unknown copy duration is never presented as an estimated percentage. */
public final class PaperPreparationUi implements AutoCloseable {
    public enum Phase { PLAYER_DATA, MAP, SPAWNS, LOOT, FLIGHT }
    public record Status(Phase phase,int completed,int total,String detailZh,String detailEn) {
        public Status { Objects.requireNonNull(phase);if(completed<0||total<0||completed>total)throw new IllegalArgumentException("Invalid preparation count"); }
        public Status(Phase phase,int completed,int total){this(phase,completed,total,"","");}
        public static Status phase(Phase phase){return new Status(phase,0,0);}
        float progress(){double fraction=total==0?0:(double)completed/total;return (float)Math.clamp((phase.ordinal()+fraction)/Phase.values().length,0,1);}
    }
    private final Server server;
    private final GameClock clock;
    private final long began;
    private final List<UUID> participants;
    private final Supplier<Status> status;
    private final Map<UUID,BossBar> bars=new HashMap<>();
    private final GameScheduler.Task task;
    private boolean closed;
    public PaperPreparationUi(Server server,GameScheduler scheduler,GameClock clock,Collection<UUID> participants,Supplier<Status> status){
        this.server=server;this.clock=clock;this.began=clock.nanoTime();this.participants=List.copyOf(participants);this.status=status;
        task=scheduler.repeat(5,this::render);
        try{render();}catch(RuntimeException|Error failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public void render(){
        if(closed)return;
        Status current=status.get();long elapsed=Math.max(0,(clock.nanoTime()-began)/1_000_000_000L);
        for(UUID id:participants){
            var player=server.getPlayer(id);
            if(player==null||!player.isOnline()){detach(id);continue;}
            var locale=I18n.locale(player);
            String stage=switch(current.phase()){
                case PLAYER_DATA->I18n.text(locale,"保存玩家状态","Saving player state");
                case MAP->I18n.text(locale,"创建比赛地图","Creating match world");
                case SPAWNS->I18n.text(locale,"检查安全落点","Checking safe landing sites");
                case LOOT->I18n.text(locale,"布置地面物资","Placing ground supplies");
                case FLIGHT->I18n.text(locale,"登机与跳伞","Boarding and deployment");
            };
            String count=current.total()>0?" "+current.completed()+"/"+current.total():"";
            String detail=I18n.chinese(locale)?current.detailZh():current.detailEn();
            String title=I18n.text(locale,"开局准备 %s/5 · %s%s · 已用时 %s 秒","Match preparation %s/5 · %s%s · %s s elapsed",current.phase().ordinal()+1,stage,count,elapsed);
            if(!detail.isBlank())title+=" · "+detail;
            BossBar bar=bars.get(id);
            if(bar==null){bar=BossBar.bossBar(UiText.text(title),current.progress(),BossBar.Color.YELLOW,BossBar.Overlay.NOTCHED_10);bars.put(id,bar);player.showBossBar(bar);}
            else bar.name(UiText.text(title)).progress(current.progress());
        }
    }
    public int size(){return bars.size();}
    public void detach(UUID id){var bar=bars.remove(id);var player=server.getPlayer(id);if(bar!=null&&player!=null)player.hideBossBar(bar);}
    @Override public void close(){if(closed)return;closed=true;task.cancel();for(UUID id:List.copyOf(bars.keySet()))detach(id);}
}
