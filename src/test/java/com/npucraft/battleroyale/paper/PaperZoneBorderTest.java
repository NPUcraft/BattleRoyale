package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.ZoneUiSettings;
import com.npucraft.battleroyale.zone.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import org.bukkit.Server;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the per-player border never mutates the server world border and folds back on every exit. */
class PaperZoneBorderTest {
    private static ZoneUiSettings settings(boolean enabled){
        return new ZoneUiSettings(true,5,true,"END_ROD",5,64,2.5,1.5,3,6,20,true,5,true,enabled);
    }
    private static ZoneRuntime waiting(){
        var profile=new ZoneProfile("t",List.of(new ZoneProfile.InitialSize(8,500)),
                List.of(new ZoneProfile.Stage(Duration.ofSeconds(90),Duration.ofSeconds(60),250,1,0,1)));
        return new ZoneRuntime(new Zone(0,0,500),profile,new Random(1),0);
    }
    private static ZoneRuntime shrinkingHalfway(){
        var profile=new ZoneProfile("t",List.of(new ZoneProfile.InitialSize(8,500)),
                List.of(new ZoneProfile.Stage(Duration.ZERO,Duration.ofSeconds(20),250,1,0,1)));
        var runtime=new ZoneRuntime(new Zone(0,0,500),profile,new Random(1),0);runtime.update(10_000_000_000L);return runtime;
    }
    private static ZoneRuntime finalZero(){
        var profile=new ZoneProfile("t",List.of(new ZoneProfile.InitialSize(8,500)),
                List.of(new ZoneProfile.Stage(Duration.ZERO,Duration.ofSeconds(1),0,1,0,1)));
        var runtime=new ZoneRuntime(new Zone(0,0,500),profile,new Random(1),0);runtime.update(2_000_000_000L);return runtime;
    }
    @Test void disabledSettingNeverCreatesOrTouchesABorder(){
        var server=new ServerRecorder();var player=new PlayerRecorder();
        var border=new PaperZoneBorder(server.server,settings(false));
        assertFalse(border.enabled());assertNull(border.border());assertEquals(0,server.created);
        border.apply(player.player,waiting());
        border.restore(player.id);
        assertTrue(player.borders.isEmpty());
    }
    @Test void applyUsesOneSharedBorderWithZeroDamageAndSubscribesOnce(){
        var server=new ServerRecorder();var player=new PlayerRecorder();var zone=waiting();
        var border=new PaperZoneBorder(server.server,settings(true));
        border.apply(player.player,zone);
        assertEquals(1,server.created);assertEquals(1,border.size());
        assertEquals(0,server.border.damageAmount);assertEquals(0,server.border.damageBuffer);
        assertEquals(0,server.border.warningTime);assertEquals(0,server.border.warningDistance);
        assertEquals(2*(zone.current().halfSize()+PaperZoneBorder.MARGIN),server.border.size.doubleValue(),.001);
        assertEquals(List.of(border.border()),player.borders);
        border.apply(player.player,zone);
        assertEquals(1,player.borders.size());
    }
    @Test void absoluteTrackingPushesOnlyWhenTheSquareMoves(){
        var server=new ServerRecorder();var player=new PlayerRecorder();
        var border=new PaperZoneBorder(server.server,settings(true));
        var waiting=waiting();
        border.apply(player.player,waiting);
        assertEquals(2*(waiting.current().halfSize()+PaperZoneBorder.MARGIN),server.border.size.doubleValue(),.001);
        assertEquals(1,server.border.sizePushes);
        border.apply(player.player,waiting);
        assertEquals(1,server.border.sizePushes);
        var shrinking=shrinkingHalfway();
        border.apply(player.player,shrinking);
        assertEquals(2*(shrinking.current().halfSize()+PaperZoneBorder.MARGIN),server.border.size.doubleValue(),.001);
        assertEquals(2,server.border.sizePushes);
    }
    @Test void finalCollapseRestoresTheServerBorderInsteadOfCagingAnEmptySquare(){
        var server=new ServerRecorder();var player=new PlayerRecorder();var zone=finalZero();
        server.player=player.player;
        var border=new PaperZoneBorder(server.server,settings(true));
        border.apply(player.player,waiting());
        assertEquals(1,border.size());
        border.apply(player.player,zone);
        assertEquals(Arrays.asList(border.border(),null),player.borders);
        assertEquals(0,border.size());
    }
    @Test void restoreAllFoldsEveryViewerBackOnTheServerBorder(){
        var server=new ServerRecorder();var player=new PlayerRecorder();
        server.player=player.player;
        var border=new PaperZoneBorder(server.server,settings(true));
        border.apply(player.player,waiting());
        border.restoreAll();
        assertEquals(0,border.size());assertEquals(0,server.border.damageAmount);
        assertEquals(Arrays.asList(border.border(),null),player.borders);
        border.restoreAll();
        assertEquals(2,player.borders.size());
    }
    private static final class BorderRecorder {
        Double size,centerX,centerZ;int sizePushes;
        double damageAmount=-1,damageBuffer=-1;int warningTime=-1,warningDistance=-1;
    }
    private static final class ServerRecorder {
        final BorderRecorder border=new BorderRecorder();
        final WorldBorder worldBorder=(WorldBorder)Proxy.newProxyInstance(WorldBorder.class.getClassLoader(),new Class<?>[]{WorldBorder.class},(proxy,method,args)-> switch (method.getName()) {
            case "setSize" -> { if(args.length!=1)throw new AssertionError("Timed setSize must not be used");border.size=(Double)args[0];border.sizePushes++;yield null; }
            case "setCenter" -> { border.centerX=(Double)args[0];border.centerZ=(Double)args[1];yield null; }
            case "setDamageAmount" -> { border.damageAmount=(Double)args[0];yield null; }
            case "setDamageBuffer" -> { border.damageBuffer=(Double)args[0];yield null; }
            case "setWarningTimeTicks" -> { border.warningTime=(Integer)args[0];yield null; }
            case "setWarningDistance" -> { border.warningDistance=(Integer)args[0];yield null; }
            case "equals" -> args[0]==proxy;
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "zone-border-test-border";
            default -> null;
        });
        int created;
        Player player;
        final Server server=(Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class<?>[]{Server.class},(proxy,method,args)->{
            if(method.getName().equals("createWorldBorder")){created++;return worldBorder;}
            if(method.getName().equals("getPlayer"))return player;
            throw new AssertionError("Server API must not be called: "+method.getName());
        });
    }
    private static final class PlayerRecorder {
        final UUID id=UUID.randomUUID();final List<WorldBorder> borders=new ArrayList<>();
        final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "setWorldBorder" -> { borders.add((WorldBorder)args[0]); yield null; }
                case "toString" -> "zone-border-test-viewer";
                default -> throw new AssertionError("Unexpected player call: "+method.getName());
            };
        });
    }
}
