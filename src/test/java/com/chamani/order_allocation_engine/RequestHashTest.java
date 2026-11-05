package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.service.RequestHash;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestHashTest {

    private static final UUID ITEM = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void sameInput_sameHash() {
        assertEquals(RequestHash.of(ITEM, 10), RequestHash.of(ITEM, 10));
    }

    @Test
    void differentQuantity_differentHash() {
        assertNotEquals(RequestHash.of(ITEM, 10), RequestHash.of(ITEM, 11));
    }

    @Test
    void differentItem_differentHash() {
        assertNotEquals(RequestHash.of(ITEM, 10), RequestHash.of(UUID.randomUUID(), 10));
    }

    @Test
    void hashIs64LowercaseHexChars() {
        String hash = RequestHash.of(ITEM, 10);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
