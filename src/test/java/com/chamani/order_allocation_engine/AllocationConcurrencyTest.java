package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.service.AllocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AllocationService against the real PostgreSQL database
 * configured in application.properties (needs the DB_PASSWORD env var, same as the app).
 * Each test creates its own inventory item, so tests do not interfere with each other.
 */
@SpringBootTest
class AllocationConcurrencyTest {

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void concurrentRequests_neverOversell() throws Exception {
        final int stock = 100;
        final int quantityPerOrder = 10;
        final int requests = 20;                       // 20 x 10 = 200 requested, only 100 available

        UUID itemId = inventoryItemRepository.save(new InventoryItem(stock)).getItemId();

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);

        AtomicInteger allocated = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            final String key = "junit-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();                        // release all threads at the same instant
                    Order order = allocationService.allocate(itemId, quantityPerOrder, key);
                    if (order.getStatus() == OrderStatus.ALLOCATED) {
                        allocated.incrementAndGet();
                    } else if (order.getStatus() == OrderStatus.FAILED) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                    e.printStackTrace();
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not start in time");
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int remaining = inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable();

        assertEquals(0, unexpected.get(), "no request may fail with an unexpected error (e.g. lock conflicts)");
        assertEquals(stock / quantityPerOrder, allocated.get(), "exactly 10 orders should be allocated");
        assertEquals(requests - allocated.get(), rejected.get(), "all other requests should be rejected for insufficient stock");
        assertEquals(0, remaining, "stock must be fully consumed and never negative");
    }

    @Test
    void sameIdempotencyKey_returnsSameOrder_andDeductsStockOnce() {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        String key = "junit-idem-" + UUID.randomUUID();

        Order first = allocationService.allocate(itemId, 10, key);
        Order second = allocationService.allocate(itemId, 10, key);

        assertEquals(first.getOrderId(), second.getOrderId());
        assertEquals(90, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable());
    }

    @Test
    void insufficientStock_persistsFailedOrder_andLeavesStockUnchanged() {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(5)).getItemId();

        Order order = allocationService.allocate(itemId, 10, "junit-fail-" + UUID.randomUUID());

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertNotNull(order.getOrderId(), "the FAILED order must be saved, not rolled back");

        Order stored = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FAILED, stored.getStatus());
        assertTrue(stored.getFailureReason().contains("only 5 available"));

        assertEquals(5, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable(),
                "a rejected order must not touch stock");
    }

    @Test
    void retryOfFailedKey_returnsSameFailedOrder_evenAfterRestock() {
        InventoryItem item = inventoryItemRepository.save(new InventoryItem(5));
        String key = "junit-fail-retry-" + UUID.randomUUID();

        Order first = allocationService.allocate(item.getItemId(), 10, key);

        // Restock so the same request would now succeed with a NEW key
        item.setQuantityAvailable(100);
        inventoryItemRepository.save(item);

        Order retry = allocationService.allocate(item.getItemId(), 10, key);

        assertEquals(first.getOrderId(), retry.getOrderId(), "an idempotency key replays its stored outcome");
        assertEquals(OrderStatus.FAILED, retry.getStatus());
        assertEquals(100, inventoryItemRepository.findById(item.getItemId()).orElseThrow().getQuantityAvailable());
    }

    @Test
    void unknownItem_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> allocationService.allocate(UUID.randomUUID(), 5, "junit-" + UUID.randomUUID()));
    }
}
