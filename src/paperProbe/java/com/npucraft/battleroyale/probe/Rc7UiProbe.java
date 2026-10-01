package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.config.ZoneUiSettings;
import com.npucraft.battleroyale.paper.PaperZoneUi;
import com.npucraft.battleroyale.zone.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Paper-loaded UI objects and a strict Player-interface recorder; no native client claim. */
public final class Rc7UiProbe {
    private final JavaPlugin plugin;
    private boolean busy;
    public Rc7UiProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc7"),"Explicit isolated rc7 flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real online players during probe");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc7ui all");
        require(!busy,"UI probe already running");busy=true;
        try{
            var report=new YamlConfiguration();report.set("platform",plugin.getServer().getVersion());geometry(report);render(report);
            report.set("status","passed");report.set("limits",List.of("Paper-loaded BossBar and Component API with Player interface recorder only.","No native client packets, actionbar visibility, real player inputs, or full match is claimed.","No world, terrain, inventory, player state or world-border mutation is performed."));
            var output=plugin.getDataFolder().toPath().resolve("rc7-ui/report.yml");byte[] bytes=report.saveToString().getBytes(StandardCharsets.UTF_8);
            CompletableFuture.runAsync(()->{try{AtomicFiles.write(output,bytes);}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}})
                    .whenComplete((unused,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
                        busy=false;if(error==null)sender.sendMessage("RC7 UI SUCCESS next-square-distance=OK center-arrows=OK airdrop=OK area=OK stages=8 path="+output.toAbsolutePath());else fail(sender,error);
                    }));
        }catch(Throwable error){busy=false;fail(sender,error);}
    }
    private void geometry(YamlConfiguration report){
        var current=new Zone(0,0,100);var next=new Zone(-50,-50,40);
        var outside=ZoneNavigation.guide(current,next,120,30,0);
        require(outside.outside()&&outside.targetX()==-50&&outside.targetZ()==-50,"Outside current still targets next center");
        require(Math.abs(outside.distance()-Math.hypot(130,40))<1e-9,"Distance is nearest next-square boundary");
        require(outside.arrow().equals("↘")&&ZoneNavigation.arrow(-130,-40,0).equals("→"),"Arrow is center bearing, not nearest-boundary bearing");
        var corner=ZoneNavigation.guide(current,next,-7,-6,0);require(corner.distance()==5&&corner.outside(),"3-4-5 outside corner distance");
        var inside=ZoneNavigation.guide(current,next,-47,-46,0);require(inside.distance()==5&&!inside.outside(),"Inside next uses center distance");
        var edge=ZoneNavigation.guide(current,next,-10,-10,0);require(!edge.outside()&&Math.abs(edge.distance()-Math.hypot(40,40))<1e-9,"Exact next-square corner is inside");
        var finalHint=ZoneNavigation.guide(current,null,103,104,0);require(finalHint.outside()&&finalHint.distance()==5&&finalHint.targetX()==0&&finalHint.targetZ()==0,"Final fallback uses current square");
        require(ZoneNavigation.arrow(0,10,0).equals("↑")&&ZoneNavigation.arrow(0,10,90).equals("←")&&ZoneNavigation.arrow(-10,0,-270).equals("↑"),"Bukkit yaw and unwrapped yaw bearings");
        report.set("geometry.outside-current-next-distance",outside.distance());report.set("geometry.outside-current-center-arrow",outside.arrow());report.set("geometry.square-corner-distance",corner.distance());report.set("geometry.inside-next-center-distance",inside.distance());report.set("geometry.final-fallback-distance",finalHint.distance());
    }
    private void render(YamlConfiguration report){
        World world=plugin.getServer().getWorlds().getFirst();double borderSize=world.getWorldBorder().getSize();Location borderCenter=world.getWorldBorder().getCenter();
        var position=new Location(world,499,80,499,0,0);UUID id=UUID.randomUUID();var messages=new ArrayList<Component>();var shown=new ArrayList<BossBar>();var hidden=new ArrayList<BossBar>();Locale[] locale={Locale.SIMPLIFIED_CHINESE};
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,method,args)->switch(method.getName()){
            case "getUniqueId"->id;case "getLocation"->position.clone();case "locale"->locale[0];
            case "sendActionBar"->{messages.add((Component)args[0]);yield null;}
            case "showBossBar"->{shown.add((BossBar)args[0]);yield null;}
            case "hideBossBar"->{hidden.add((BossBar)args[0]);yield null;}
            case "toString"->"Rc7UiPlayerInterfaceRecorder";
            default->throw new IllegalStateException("Unexpected UI Player API "+method);
        });
        Server server=(Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class<?>[]{Server.class},(self,method,args)->{
            if(method.getName().equals("getPlayer"))return id.equals(args[0])?player:null;
            throw new IllegalStateException("Unexpected UI Server API "+method);
        });
        var stages=new ArrayList<ZoneProfile.Stage>();for(int i=0;i<8;i++)stages.add(new ZoneProfile.Stage(Duration.ofSeconds(1),Duration.ofSeconds(1),400-i*40,0,0,0));
        var profile=new ZoneProfile("rc7-ui",List.of(new ZoneProfile.InitialSize(8,500)),stages);
        var zone=new ZoneRuntime(new Zone(0,0,500),profile,new Random(7),0);
        var settings=new ZoneUiSettings(true,5,false,"END_ROD",5,64,2.5,1.5,3,6,20,true,5,true);var ui=new PaperZoneUi(server,settings);
        try{
            ui.render(player,zone,0,8,2,4,false,new ZoneNavigation.Point(502,503));
            require(shown.size()==1&&ui.size()==1,"One real BossBar object per viewer");
            String waiting=plain(shown.getFirst().name()),action=plain(messages.getLast());noCoordinates(waiting);noCoordinates(action);
            require(waiting.contains("第 1/8 阶段")&&waiting.contains("下圈面积 800² ㎡"),"BossBar reports stage fraction and next-square area");
            require(action.contains("下圈边界")&&action.endsWith("↖ 空投 5 米"),"Bottom bar combines safe-square and independent airdrop navigation");
            report.set("waiting.bossbar",waiting);report.set("waiting.actionbar",action);
            locale[0]=Locale.ENGLISH;ui.render(player,zone,0,8,2,4,false,new ZoneNavigation.Point(502,503));
            String englishBar=plain(shown.getFirst().name()),englishAction=plain(messages.getLast());
            require(englishBar.contains("Stage 1/8")&&englishBar.contains("Next zone area 800² m²"),"English BossBar retains phase and area semantics");
            require(englishAction.contains("Next zone edge")&&englishAction.endsWith("↖ Airdrop 5 m"),"English independent airdrop navigation");
            locale[0]=null;ui.render(player,zone,0,8,2,4,false,new ZoneNavigation.Point(502,503));
            require(plain(shown.getFirst().name()).equals(englishBar)&&plain(messages.getLast()).equals(englishAction)&&shown.size()==1,"Unknown locale falls back to English without replacing the owned BossBar");
            report.set("language.english-bossbar",englishBar);report.set("language.english-actionbar",englishAction);report.set("language.live-switch-and-unknown-fallback",true);
            locale[0]=Locale.SIMPLIFIED_CHINESE;
            position.setX(zone.next().centerX()+3);position.setZ(zone.next().centerZ()+4);
            ui.render(player,zone,5,8,2,4,false,new ZoneNavigation.Point(position.getX()+7,position.getZ()));
            String inside=plain(messages.getLast());require(inside.contains("下圈中心 5 米")&&inside.endsWith("← 空投 7 米"),"Inside-next guidance switches to center distance");noCoordinates(inside);report.set("inside-next.actionbar",inside);
            position.setYaw(90);ui.render(player,zone,10,8,2,4,false,new ZoneNavigation.Point(position.getX()+7,position.getZ()));
            require(plain(messages.getLast()).endsWith("↓ 空投 7 米"),"Airdrop arrow rotates with viewer yaw");
            for(int i=1;i<8;i++){zone.update(i*2_000_000_000L);require(zone.stageNumber()==i+1&&zone.stageCount()==8,"Configured stage progression");}
            zone.update(16_000_000_000L);
            require(zone.phase()==ZonePhase.SHRINKING&&zone.stageNumber()==8&&zone.current().halfSize()==120&&zone.next().halfSize()==0,"Legacy last target continues to zero within stage eight");
            var saved=zone.snapshot();var restored=ZoneRuntime.restore(saved,profile,new Random(99),100_000_000_000L);
            require(restored.snapshot().equals(saved),"Final continuation recovers without moving its center or consuming downtime");
            zone.update(19_000_000_000L);ui.render(player,zone,15,2,3,1,false,null);
            String finalBar=plain(shown.getFirst().name()),finalAction=plain(messages.getLast());noCoordinates(finalBar);noCoordinates(finalAction);
            require(finalBar.contains("第 8/8 阶段")&&!finalBar.contains("第 9/")&&finalBar.contains("安全区面积 0² ㎡"),"FINAL remains 8/8 with final-square area");
            require(finalAction.equals("安全区已消失")&&zone.current().halfSize()==0,"Closed zone no longer advertises a safe center or stale supply marker");
            position.setX(zone.current().centerX());position.setZ(zone.current().centerZ());ui.render(player,zone,20,2,3,1,false,null);
            require(shown.getFirst().color()==BossBar.Color.RED&&ZoneDamage.amount(zone.current(),position.getX(),position.getZ(),zone.stage())>=1,"Collapsed center is outside and takes damage even with a zero-damage legacy profile");
            report.set("final.zero-center-damage",true);report.set("final.legacy-tail-restored",true);
            report.set("final.bossbar",finalBar);report.set("final.actionbar",finalAction);
            ui.render(player,zone,20,2,3,1,true,new ZoneNavigation.Point(0,0));require(plain(messages.getLast()).isEmpty(),"Spectators clear both actionbar targets");
            ui.render(player,zone,25);require(!plain(messages.getLast()).contains("空投"),"Legacy overload remains usable without airdrop point");
            ui.detach(id);require(ui.size()==0&&shown.equals(hidden)&&plain(messages.getLast()).isEmpty(),"Detach hides owned BossBar and clears navigation");
            require(world.getWorldBorder().getSize()==borderSize&&world.getWorldBorder().getCenter().equals(borderCenter),"Native world border is unchanged");
            report.set("lifecycle","one owned BossBar; detach/legacy overload/spectator clearing passed");report.set("world-border","unchanged");
        }finally{ui.close();}
    }
    private void fail(CommandSender sender,Throwable error){sender.sendMessage("RC7 UI FAILED "+error);plugin.getLogger().log(Level.SEVERE,"RC7 UI FAILED",error);}
    private static void noCoordinates(String text){require(!text.contains("X:")&&!text.contains("Z:")&&!text.contains("X：")&&!text.contains("Z："),"No current/next coordinates in UI");}
    private static String plain(Component component){return PlainTextComponentSerializer.plainText().serialize(component);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("RC7 UI assertion: "+message);}
}
