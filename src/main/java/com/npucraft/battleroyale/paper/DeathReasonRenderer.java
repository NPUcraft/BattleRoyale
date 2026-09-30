package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.combat.DeathReason;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.function.Function;
public final class DeathReasonRenderer {
    private DeathReasonRenderer() {}
    public static Component render(DeathReason reason,Function<UUID,String> names) {
        return render(Locale.SIMPLIFIED_CHINESE,reason,names);
    }
    public static Component render(org.bukkit.command.CommandSender viewer,DeathReason reason,Function<UUID,String> names){return render(I18n.locale(viewer),reason,names);}
    public static Component render(Locale locale,DeathReason reason,Function<UUID,String> names){
        String[] causes=cause(reason);
        String cause=I18n.text(locale,causes[0],causes[1]);
        return reason.killer().map(id -> UiText.muted(I18n.text(locale,"被 ","Eliminated by ")).append(UiText.value(names.apply(id)))
                .append(UiText.error(I18n.text(locale," 淘汰",""))).append(UiText.muted(" ("+cause+")")))
            .orElseGet(() -> UiText.error(I18n.text(locale,"死于","Died from ")).append(UiText.warning(cause)));
    }
    public static Component shared(DeathReason reason,Function<UUID,String> names){
        String[] causes=cause(reason);
        var cause=I18n.shared("cause."+reason.origin().name().toLowerCase(Locale.ROOT),causes[0],causes[1]).color(UiText.WARNING);
        return reason.killer().map(id->I18n.shared("death.killed","被 {0} 淘汰（{1}）","Eliminated by {0} ({1})",UiText.value(names.apply(id)),cause).color(UiText.ERROR))
            .orElseGet(()->I18n.shared("death.environment","死于{0}","Died from {0}",cause).color(UiText.ERROR));
    }
    private static String[] cause(DeathReason reason){
        String cause=switch(reason.origin()) {
            case PLAYER_MELEE -> "近战"; case PROJECTILE -> "投射物"; case EXPLOSION -> "爆炸";
            case FIRE -> "火焰"; case LAVA -> "熔岩"; case FALL -> "坠落"; case ZONE -> "安全区外伤害"; case DROWNING -> "溺水";
            case VOID -> "虚空"; case MOB -> "生物攻击"; case MAGIC -> "魔法"; case OTHER -> "其他伤害";
            case DISCONNECT_TIMEOUT -> "重连超时";case DISCONNECT_BODY_FAILURE -> "离线替身异常";
        };
        String english=switch(reason.origin()){
            case PLAYER_MELEE->"melee";case PROJECTILE->"a projectile";case EXPLOSION->"an explosion";
            case FIRE->"fire";case LAVA->"lava";case FALL->"a fall";case ZONE->"zone damage";case DROWNING->"drowning";
            case VOID->"the void";case MOB->"a creature attack";case MAGIC->"magic";case OTHER->"other damage";
            case DISCONNECT_TIMEOUT->"reconnect timeout";case DISCONNECT_BODY_FAILURE->"offline body failure";
        };
        return new String[]{cause,english};
    }
}
