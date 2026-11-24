package com.chamani.order_allocation_engine.fulfilment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprint of a fulfilment request's payload, stored with the order so a reused Idempotency-Key with a different request is refused (422). */
final class FulfilmentRequestHash {

    private FulfilmentRequestHash() {}

    /** @param requestedStrategy what the caller asked for, null when they left it to the tier default */
    static String of(String sku, int quantity, BigDecimal lat, BigDecimal lon, CustomerTier tier, StrategyType requestedStrategy) {
        String canonical = String.join("|", sku, Integer.toString(quantity), plain(lat), plain(lon), tier.name(),
                requestedStrategy == null ? "" : requestedStrategy.name());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // 6.9271 and 6.927100 are the same location
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
