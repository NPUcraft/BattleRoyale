package com.npucraft.lastsector.economy;
import com.npucraft.lastsector.api.economy.EconomyProvider;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import java.math.BigDecimal;
import java.util.UUID;
/** ExcellentEconomy 2.8.0 public cached API. Never blocks on async user data. */
public final class ExcellentEconomyProvider implements EconomyProvider {
    private final Server server;private final String currency;
    public ExcellentEconomyProvider(Server server,String currency){this.server=server;this.currency=currency;}
    private ExcellentEconomyAPI service(){var registration=server.getServicesManager().getRegistration(ExcellentEconomyAPI.class);return registration==null?null:registration.getProvider();}
    public boolean isAvailable(){var service=service();return service!=null && service.canPerformOperations() && service.hasCurrency(currency);}
    private ExcellentEconomyAPI required(){if(!org.bukkit.Bukkit.isPrimaryThread() || !isAvailable())throw new IllegalStateException("ExcellentEconomy unavailable or wrong thread");return service();}
    private Player player(UUID id){var player=server.getPlayer(id);if(player==null || !player.isOnline() || required().getCachedUserData(id).isEmpty())throw new IllegalStateException("Economy player data unavailable");return player;}
    public BigDecimal getBalance(UUID id){return Money.balance(required().getBalance(player(id),currency));}
    public boolean has(UUID id,BigDecimal amount){Money.amount(amount,required().getCurrency(currency).isDecimal()?-1:0);return getBalance(id).compareTo(amount)>=0;}
    public boolean deposit(UUID id,BigDecimal amount){return required().deposit(player(id),currency,Money.amount(amount,required().getCurrency(currency).isDecimal()?-1:0));}
    public boolean withdraw(UUID id,BigDecimal amount){return required().withdraw(player(id),currency,Money.amount(amount,required().getCurrency(currency).isDecimal()?-1:0));}
}
