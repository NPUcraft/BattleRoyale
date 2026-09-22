package com.npucraft.lastsector.cosmetic;
import java.math.BigDecimal;
import java.util.UUID;
public record Purchase(UUID id,UUID player,String cosmetic,String provider,String currency,BigDecimal amount,
                       Status status,long createdAt,long updatedAt) {
    public enum Status { PENDING, WITHDRAWING, COMPLETED, REFUNDED, MANUAL_REVIEW, CANCELLED }
}
