package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.progression.MatchResult;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.service.UiText;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Trial economy payouts: +200 per credited kill and top-3 settlement bonuses (1000/500/300).
 *  Every call site runs on the primary thread. Payouts never block match flow: an unavailable
 *  economy adapter or an offline recipient skips the payment instead of failing the match. */
public final class PaperEconomyRewards {
    public static final long KILL_REWARD=200;
    public static final long[] SETTLEMENT={1000,500,300};
    private PaperEconomyRewards() {}
    /** Kill reward: credited when a victim is eliminated with a credited, distinct attacker. */
    public static void killReward(PaperProgression progression,UUID attacker) {
        pay(progression,attacker,KILL_REWARD,"击杀奖励","kill reward");
    }
    /** End-of-match bonuses: official results only, paid per player by team placement (1st/2nd/3rd). */
    public static void settle(PaperProgression progression,MatchResult result) {
        if(result==null || !result.official())return;
        for(var player:result.players()) {
            int placement=player.placement();
            if(placement>=1&&placement<=SETTLEMENT.length)
                pay(progression,player.playerId(),SETTLEMENT[placement-1],"结算奖励 · 第 "+placement+" 名","settlement bonus · place "+placement);
        }
    }
    private static void pay(PaperProgression progression,UUID id,long amount,String zh,String en) {
        try {
            var economy=progression.economy();
            if(!economy.available())return;
            Player player=Bukkit.getPlayer(id);
            if(player==null)return;
            if(!economy.provider().deposit(id,BigDecimal.valueOf(amount)))return;
            player.sendMessage(UiText.message(I18n.text(player,"+"+amount+" 金币（"+zh+"）","+"+amount+" coins ("+en+")"))
                    .append(UiText.muted(" · ").append(UiText.value("余额 "+format(balance(economy,id).doubleValue())))));
            player.playSound(player.getLocation(),Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.8f,1.4f);
        } catch(LinkageError|RuntimeException error) {
            Bukkit.getLogger().warning("[BattleRoyale] Economy payout skipped for "+id+": "+error.getClass().getSimpleName());
        }
    }
    private static BigDecimal balance(com.npucraft.battleroyale.economy.EconomySelection economy,UUID id) {
        try {return economy.provider().getBalance(id);} catch(LinkageError|RuntimeException error) {return BigDecimal.ZERO;}
    }
    /** Plain decimal text; the backing currency decides its own precision, we only render. */
    public static String format(double value) {
        if(Double.isNaN(value)||Double.isInfinite(value))return "?";
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
