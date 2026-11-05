package com.chamani.order_allocation_engine.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Fingerprint of an order request's payload (itemId + quantity). Stored with the order so a
 * reused Idempotency-Key can be checked against it without comparing every field, which keeps
 * working when the request grows.
 */
public final class RequestHash {

    private RequestHash() {}

    public static String of(UUID itemId, Integer quantity) {
        try {
            // MessageDigest is not thread-safe, so create one per call.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((itemId + ":" + quantity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
