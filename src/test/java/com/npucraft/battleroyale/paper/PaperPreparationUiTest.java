package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.GameScheduler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperPreparationUiTest {
    @Test void visibleImmediatelyAndUsesStageCountsWithoutInventingMapCopyPercent(){
        var viewer=new Viewer();var schedule=new Schedule();long[] now={0};
        var status=new AtomicReference<>(PaperPreparationUi.Status.phase(PaperPreparationUi.Phase.PLAYER_DATA));
        var ui=new PaperPreparationUi(viewer.server,schedule,()->now[0],List.of(viewer.id),status::get);
        assertEquals(1,viewer.shown.size());var owned=viewer.shown.getFirst();assertTrue(text(owned).contains("开局准备 1/5"));
        now[0]=31_000_000_000L;status.set(PaperPreparationUi.Status.phase(PaperPreparationUi.Phase.MAP));schedule.tick();
        assertTrue(text(owned).contains("创建比赛地图"));assertTrue(text(owned).contains("31 秒"));assertFalse(text(owned).contains("%"));
        float mapProgress=owned.progress();status.set(new PaperPreparationUi.Status(PaperPreparationUi.Phase.SPAWNS,2,4));schedule.tick();
        assertTrue(text(owned).contains("2/4"));assertTrue(owned.progress()>mapProgress);
        viewer.locale=Locale.JAPANESE;status.set(new PaperPreparationUi.Status(PaperPreparationUi.Phase.FLIGHT,3,4,"等待队员着陆","Waiting for landings"));schedule.tick();
        assertTrue(text(owned).contains("3/4"));assertTrue(text(owned).contains("Waiting for landings"));assertEquals(1,viewer.shown.size());
        ui.close();ui.close();assertTrue(schedule.cancelled);assertEquals(List.of(owned),viewer.hidden);assertEquals(0,ui.size());
        ui.render();assertEquals(1,viewer.shown.size());
    }
    @Test void disconnectedViewerReleasesOwnedBarAndReconnectGetsCurrentLanguage(){
        var viewer=new Viewer();var schedule=new Schedule();
        var ui=new PaperPreparationUi(viewer.server,schedule,()->0,List.of(viewer.id),()->PaperPreparationUi.Status.phase(PaperPreparationUi.Phase.MAP));
        viewer.online=false;schedule.tick();assertEquals(0,ui.size());assertEquals(1,viewer.hidden.size());
        viewer.online=true;viewer.locale=null;schedule.tick();assertEquals(1,ui.size());assertTrue(text(viewer.shown.getLast()).contains("Creating match world"));
        ui.close();assertEquals(viewer.shown,viewer.hidden);
    }
    @Test void failedInitialRenderCancelsItsUnpublishedTask(){
        var viewer=new Viewer();var schedule=new Schedule();
        assertThrows(IllegalStateException.class,()->new PaperPreparationUi(viewer.server,schedule,()->0,List.of(viewer.id),()->{throw new IllegalStateException("Status unavailable");}));
        assertTrue(schedule.cancelled);assertTrue(viewer.shown.isEmpty());schedule.tick();
    }
    private static String text(BossBar bar){return PlainTextComponentSerializer.plainText().serialize(bar.name());}
    private static final class Schedule implements GameScheduler{
        Runnable task;boolean cancelled;
        public Task repeat(int period,Runnable action){assertEquals(5,period);task=action;return ()->cancelled=true;}
        void tick(){if(!cancelled)task.run();}
    }
    private static final class Viewer{
        final UUID id=UUID.randomUUID();boolean online=true;Locale locale=Locale.CHINESE;
        final List<BossBar> shown=new ArrayList<>(),hidden=new ArrayList<>();
        final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()){
            case "getUniqueId"->id;case "locale"->locale;case "isOnline"->online;
            case "showBossBar"->{shown.add((BossBar)args[0]);yield null;}
            case "hideBossBar"->{hidden.add((BossBar)args[0]);yield null;}
            default->throw new AssertionError("Unexpected preparation UI player call: "+method.getName());
        });
        final Server server=(Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class<?>[]{Server.class},(proxy,method,args)->{
            if(method.getName().equals("getPlayer"))return id.equals(args[0])?player:null;
            throw new AssertionError("Unexpected preparation UI server call: "+method.getName());
        });
    }
}
