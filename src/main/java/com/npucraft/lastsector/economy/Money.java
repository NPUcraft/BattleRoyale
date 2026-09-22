package com.npucraft.lastsector.economy;
import java.math.*;
/** Conversion happens only at the external double boundary. Reject silently changed prices. */
public final class Money {
    private Money() {}
    public static double amount(BigDecimal amount,int fractionalDigits) {
        if(amount==null || amount.signum()<0 || amount.precision()>32 || Math.abs((long)amount.scale())>32)
            throw new IllegalArgumentException("Invalid money amount");
        BigDecimal normalized=fractionalDigits<0?amount:amount.setScale(fractionalDigits,RoundingMode.UNNECESSARY);
        double value=normalized.doubleValue();
        if(!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(normalized)!=0)
            throw new IllegalArgumentException("Amount cannot be represented by economy provider");
        return value;
    }
    public static BigDecimal balance(double value) {
        if(!Double.isFinite(value))throw new IllegalStateException("Non-finite provider balance");
        return BigDecimal.valueOf(value);
    }
}
