package com.npucraft.battleroyale.economy;
import com.npucraft.battleroyale.api.economy.EconomyProvider;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import java.math.BigDecimal;
import java.util.UUID;
/** Loaded only after Vault is enabled. Vault always uses its provider's default currency. Main thread only. */
public final class VaultEconomyProvider implements EconomyProvider {
    private final Server server;
    public VaultEconomyProvider(Server server){this.server=server;}
    private Economy service(){var registration=server.getServicesManager().getRegistration(Economy.class);return registration==null?null:registration.getProvider();}
    public boolean isAvailable(){var service=service();return service!=null && service.isEnabled();}
    private Economy required(){if(!org.bukkit.Bukkit.isPrimaryThread())throw new IllegalStateException("Economy must run on server thread");var service=service();if(service==null || !service.isEnabled())throw new IllegalStateException("Vault economy unavailable");return service;}
    public BigDecimal getBalance(UUID player){return Money.balance(required().getBalance(server.getOfflinePlayer(player)));}
    public boolean has(UUID player,BigDecimal amount){var service=required();return service.has(server.getOfflinePlayer(player),Money.amount(amount,service.fractionalDigits()));}
    public boolean deposit(UUID player,BigDecimal amount){var service=required();return service.depositPlayer(server.getOfflinePlayer(player),Money.amount(amount,service.fractionalDigits())).transactionSuccess();}
    public boolean withdraw(UUID player,BigDecimal amount){var service=required();return service.withdrawPlayer(server.getOfflinePlayer(player),Money.amount(amount,service.fractionalDigits())).transactionSuccess();}
}
