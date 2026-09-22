package com.lastsector.economy;
import com.lastsector.api.economy.EconomyProvider;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import org.bukkit.Server;
import java.math.BigDecimal;
import java.util.UUID;
/** CoinsEngine 2.7.0 legacy static API, separate from the ExcellentEconomy service adapter. */
@SuppressWarnings("deprecation")
public final class LegacyCoinsEngineEconomyProvider implements EconomyProvider {
    private final Server server;private final String currency;
    public LegacyCoinsEngineEconomyProvider(Server server,String currency){this.server=server;this.currency=currency;}
    public boolean isAvailable(){return CoinsEngineAPI.isLoaded() && CoinsEngineAPI.hasCurrency(currency);}
    private void check(UUID id){if(!org.bukkit.Bukkit.isPrimaryThread() || !isAvailable() || server.getPlayer(id)==null)throw new IllegalStateException("CoinsEngine unavailable or player offline");}
    private double amount(BigDecimal value){return Money.amount(value,CoinsEngineAPI.getCurrency(currency).isDecimal()?-1:0);}
    public BigDecimal getBalance(UUID id){check(id);return Money.balance(CoinsEngineAPI.getBalance(id,currency));}
    public boolean has(UUID id,BigDecimal value){check(id);amount(value);return getBalance(id).compareTo(value)>=0;}
    public boolean deposit(UUID id,BigDecimal value){check(id);return CoinsEngineAPI.addBalance(id,currency,amount(value));}
    public boolean withdraw(UUID id,BigDecimal value){check(id);return CoinsEngineAPI.removeBalance(id,currency,amount(value));}
}
