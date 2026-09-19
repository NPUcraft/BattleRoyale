package com.lastsector.api.economy;
import java.math.BigDecimal;
import java.util.UUID;
/**
 * External economy boundary. Implementations document currency, precision and thread requirements.
 * Amounts must be nonnegative; a false transaction result means no balance change.
 */
public interface EconomyProvider {
    /** Whether the backing plugin and selected currency can currently be used. */
    boolean isAvailable();
    /** Returns the balance in the adapter's documented currency, without lossy float conversion. */
    BigDecimal getBalance(UUID playerId);
    /** Adds a nonnegative amount atomically; false means no change was made. */
    boolean deposit(UUID playerId, BigDecimal amount);
    /** Removes a nonnegative amount atomically; insufficient funds must return false without changing balance. */
    boolean withdraw(UUID playerId, BigDecimal amount);
    /** Tests affordability only; implementations must still recheck atomically during withdrawal. */
    boolean has(UUID playerId, BigDecimal amount);
}
