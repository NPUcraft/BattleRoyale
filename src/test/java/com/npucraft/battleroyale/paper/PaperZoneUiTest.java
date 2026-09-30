package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.ZoneUiSettings;
import com.npucraft.battleroyale.zone.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperZoneUiTest {
    private static final PlainTextComponentSerializer TEXT=PlainTextComponentSerializer.plainText();
    private static ZoneRuntime zone() {
        var profile=new ZoneProfile("test",List.of(new ZoneProfile.InitialSize(8,500)),
                List.of(new ZoneProfile.Stage(Duration.ofSeconds(10),Duration.ofSeconds(20),250,1,0,1)));
        return new ZoneRuntime(new Zone(0,0,500),profile,new Random(1),0);
    }
    private static ZoneUiSettings settings(boolean navigation,boolean wall,int cap) {
        return new ZoneUiSettings(false,5,wall,"END_ROD",5,64,2.5,1.5,3,6,cap,navigation,5,true);
    }
    @Test void messageContainsNextBoundaryDistanceAndNoCoordinates() {
        var current=new Zone(100,-200,50); var next=new Zone(90,-190,20);
        var hint=ZoneNavigation.guide(current,next,170,-200,0);
        var component=PaperZoneUi.navigationMessage(hint,current,next); String message=TEXT.serialize(component);
        assertEquals("→ 下圈边界 60 米",message);assertFalse(message.contains("X:"));assertFalse(message.contains("Z:"));
        assertEquals(com.npucraft.battleroyale.service.UiText.ERROR,component.color());
    }
    @Test void finalCenterArrivalDoesNotInventANextCircleOrHeading() {
        var current=new Zone(100,-200,50); var hint=ZoneNavigation.guide(current,null,100,-200,0);
        String text=TEXT.serialize(PaperZoneUi.navigationMessage(hint,current,null));
        assertEquals("● 安全区中心 0 米",text);assertFalse(text.contains("下圈"));
    }
    @Test void bottomBarCombinesIndependentCenterArrowsAndHorizontalDistancesForCircleAndAirdrop(){
        var current=new Zone(100,-200,50);var next=new Zone(90,-190,20);var point=new ZoneNavigation.Point(170,-195);
        var hint=ZoneNavigation.guide(current,next,170,-200,0);
        assertEquals("→ 下圈边界 60 米 · ↑ 空投 5 米",TEXT.serialize(PaperZoneUi.navigationMessage(hint,current,next,point,170,-200,0)));
        var inside=ZoneNavigation.guide(current,next,90,-185,180);
        assertEquals("↑ 下圈中心 5 米 · ● 空投 0 米",TEXT.serialize(PaperZoneUi.navigationMessage(inside,current,next,new ZoneNavigation.Point(90,-185),90,-185,180)));
    }
    @Test void bossbarUsesNextSquareAreaAndConfiguredStageFractionIncludingFinal(){
        var runtime=zone();String waiting=TEXT.serialize(PaperZoneUi.bossbarMessage(runtime,7,2,4,false,0,0));
        assertTrue(waiting.contains("第 1/1 阶段"));assertTrue(waiting.contains("下圈面积 250000 ㎡"));assertTrue(waiting.contains("击杀：2"));assertFalse(waiting.contains("X:"));
        runtime.update(30_000_000_000L);String end=TEXT.serialize(PaperZoneUi.bossbarMessage(runtime,2,0,1,true,0,0));
        assertTrue(end.contains("第 1/1 阶段"));assertFalse(end.contains("第 2/"));assertTrue(end.contains("安全区面积 250000 ㎡"));assertTrue(end.contains("观战中"));
    }
    @Test void newRenderOverloadPublishesBothNavigationTargetsAndKeepsBossbarOwnership(){
        for(Locale locale:List.of(Locale.SIMPLIFIED_CHINESE,Locale.ENGLISH)){
        boolean chinese=locale.equals(Locale.SIMPLIFIED_CHINESE);
        var viewer=new Viewer(locale);var settings=new ZoneUiSettings(true,5,false,"END_ROD",5,64,2.5,1.5,3,6,20,true,5,true);
        var ui=new PaperZoneUi(viewer.server(),settings);var runtime=zone();
        ui.render(viewer.player,runtime,0,8,3,4,false,new ZoneNavigation.Point(502,503));
        assertTrue(viewer.lastText().contains(chinese?"空投 5 米":"Airdrop 5 m"));assertEquals(1,viewer.shown.size());assertTrue(TEXT.serialize(viewer.shown.getFirst().name()).contains(chinese?"下圈面积 250000 ㎡":"Next zone area 250000 m²"));
        ui.render(viewer.player,runtime,5,8,3,4,false,null);assertFalse(viewer.lastText().contains(chinese?"空投":"Airdrop"));assertEquals(1,viewer.shown.size());
        ui.close();assertEquals(viewer.shown,viewer.hidden);assertEquals("",viewer.lastText());
        }
    }
    @Test void navigationWithoutBossbarStillClearsOnDetachCloseAndSpectatorTransition() {
        for(Locale locale:List.of(Locale.SIMPLIFIED_CHINESE,Locale.ENGLISH)){
        var viewer=new Viewer(locale); var ui=new PaperZoneUi(viewer.server(),settings(true,false,20));
        ui.render(viewer.player,zone(),0); assertTrue(viewer.lastText().contains(locale.equals(Locale.SIMPLIFIED_CHINESE)?"下圈":"Next zone")); assertEquals(0,ui.size());
        ui.detach(viewer.id); assertEquals("",viewer.lastText());
        int sent=viewer.messages.size(); ui.detach(viewer.id); assertEquals(sent,viewer.messages.size());
        ui.render(viewer.player,zone(),5); ui.close(); assertEquals("",viewer.lastText());
        ui.render(viewer.player,zone(),10); ui.render(viewer.player,zone(),11,2,0,1,true);
        assertEquals("",viewer.lastText());
        }
    }
    @Test void localeChangesRefreshExistingBossbarAndUnknownLocaleFallsBackToEnglish(){
        var viewer=new Viewer(Locale.TRADITIONAL_CHINESE);var settings=new ZoneUiSettings(true,5,false,"END_ROD",5,64,2.5,1.5,3,6,20,true,5,true);
        var ui=new PaperZoneUi(viewer.server(),settings);var runtime=zone();var drop=new ZoneNavigation.Point(502,503);
        ui.render(viewer.player,runtime,0,8,3,4,false,drop);assertTrue(viewer.lastText().contains("空投 5 米"));
        BossBar owned=viewer.shown.getFirst();viewer.locale=Locale.ENGLISH;
        ui.render(viewer.player,runtime,5,8,3,4,false,drop);assertTrue(viewer.lastText().contains("Airdrop 5 m"));assertTrue(TEXT.serialize(owned.name()).contains("Kills: 3"));
        String english=viewer.lastText(),bossbar=TEXT.serialize(owned.name());viewer.locale=null;
        ui.render(viewer.player,runtime,10,8,3,4,false,drop);assertEquals(english,viewer.lastText());assertEquals(bossbar,TEXT.serialize(owned.name()));
        assertEquals(List.of(owned),viewer.shown);ui.close();assertEquals(List.of(owned),viewer.hidden);assertEquals("",viewer.lastText());
    }
    @Test void renderingKeepsConfiguredBudgetAcrossBothParticleTypesAndSpectatorsGetNone() {
        var viewer=new Viewer(Locale.SIMPLIFIED_CHINESE); var ui=new PaperZoneUi(viewer.server(),settings(false,true,12));
        ui.render(viewer.player,zone(),0); assertEquals(12,viewer.particles.size());
        assertTrue(viewer.particles.contains(Particle.DUST)); assertTrue(viewer.particles.contains(Particle.END_ROD));
        ui.render(viewer.player,zone(),1); assertEquals(12,viewer.particles.size());
        ui.render(viewer.player,zone(),5,2,0,1,true); assertEquals(12,viewer.particles.size());
        assertTrue(viewer.messages.isEmpty());
    }
    /** API-call recorder, not a real client: unexpected inventory or world-border writes fail immediately. */
    private static final class Viewer {
        final UUID id=UUID.randomUUID();
        Locale locale;
        Viewer(Locale locale){this.locale=locale;}
        final List<Component> messages=new ArrayList<>(); final List<Particle> particles=new ArrayList<>();
        final List<BossBar> shown=new ArrayList<>(),hidden=new ArrayList<>();
        final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "locale" -> locale;
                case "getLocation" -> new Location(null,499,80,499,0,0);
                case "sendActionBar" -> { messages.add((Component)args[0]); yield null; }
                case "showBossBar" -> {shown.add((BossBar)args[0]);yield null;}
                case "hideBossBar" -> {hidden.add((BossBar)args[0]);yield null;}
                case "spawnParticle" -> { assertEquals(1,args[4]); particles.add((Particle)args[0]); yield null; }
                case "toString" -> "zone-test-viewer";
                default -> throw new AssertionError("Unexpected player call: "+method.getName());
            };
        });
        Server server() {
            return (Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class<?>[]{Server.class},(proxy,method,args)->{
                if (method.getName().equals("getPlayer")) return id.equals(args[0])?player:null;
                throw new AssertionError("Unexpected server call: "+method.getName());
            });
        }
        String lastText() { return TEXT.serialize(messages.getLast()); }
    }
}
