package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.service.AllocationService;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for allocation against a real PostgreSQL (Testcontainers). The suite is
 * mode-agnostic: one subclass per allocation.locking-mode runs every test against that strategy.
 * Each test creates its own inventory items, so tests do not interfere with each other.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class AbstractAllocationConcurrencyTest {

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private OrderAllocationFacade allocationFacade;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** Total quantity recorded in allocation rows for the item, straight from the database. */
    private int allocatedInDb(UUID itemId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity_allocated), 0) FROM allocation WHERE item_id = ?",
                Integer.class, itemId);
    }

    @Test
    void hotItem_neverOversells_andKeepsStockInvariant() throws Exception {
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
                    // Through the facade, as the controller does: it owns the optimistic retry.
                    Order order = allocationFacade.allocate(itemId, quantityPerOrder, key);
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
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int remaining = inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable();

        assertEquals(0, unexpected.get(), "no request may fail with an unexpected error (e.g. lock conflicts)");
        assertEquals(stock / quantityPerOrder, allocated.get(), "exactly 10 orders should be allocated");
        assertEquals(requests - allocated.get(), rejected.get(), "all other requests should be rejected for insufficient stock");
        assertEquals(0, remaining, "stock must be fully consumed and never negative");

        // Invariant: every unit is either allocated or still on the shelf. The DB rows must agree
        // with what the callers were told.
        int allocatedQuantity = allocatedInDb(itemId);
        assertEquals(allocated.get() * quantityPerOrder, allocatedQuantity, "allocation rows match ALLOCATED responses");
        assertEquals(stock, allocatedQuantity + remaining, "sum(allocated) + remaining == initial stock");
    }

    @Test
    void manyItemsLowContention_allocateExactly_andKeepStockInvariant() throws Exception {
        final int items = 50;
        final int stock = 10;
        final int quantityPerOrder = 5;
        final int requestsPerItem = 3;                 // 15 requested per item, 10 available: 2 win, 1 loses

        List<UUID> itemIds = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            itemIds.add(inventoryItemRepository.save(new InventoryItem(stock)).getItemId());
        }

        int total = items * requestsPerItem;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentMap<UUID, AtomicInteger> allocatedPerItem = new ConcurrentHashMap<>();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (UUID itemId : itemIds) {
            for (int r = 0; r < requestsPerItem; r++) {
                final String key = "junit-spread-" + UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    try {
                        go.await();
                        Order order = allocationFacade.allocate(itemId, quantityPerOrder, key);
                        if (order.getStatus() == OrderStatus.ALLOCATED) {
                            allocatedPerItem.computeIfAbsent(itemId, k -> new AtomicInteger()).incrementAndGet();
                        } else if (order.getStatus() != OrderStatus.FAILED) {
                            unexpected.incrementAndGet();
                        }
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                        e.printStackTrace();
                    }
                }));
            }
        }

        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, unexpected.get(), "no request may fail with an unexpected error (" + total + " requests)");
        for (UUID itemId : itemIds) {
            int remaining = inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable();
            int allocated = allocatedPerItem.getOrDefault(itemId, new AtomicInteger()).get();

            assertEquals(stock / quantityPerOrder, allocated, "two of the three requests fit");
            assertEquals(0, remaining);
            assertEquals(stock, allocatedInDb(itemId) + remaining, "sum(allocated) + remaining == initial stock");
        }
    }

    @Test
    void sameKey_tenConcurrentThreads_createOneOrder() throws Exception {
        final int threads = 10;
        UUID itemId = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        final String key = "junit-same-key-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        AtomicInteger errors = new AtomicInteger();
        Set<UUID> orderIds = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();                        // release all threads at the same instant
                    Order order = allocationFacade.allocate(itemId, 10, key);
                    orderIds.add(order.getOrderId());
                } catch (Exception e) {
                    errors.incrementAndGet();
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

        assertEquals(0, errors.get(), "no retry may fail");
        assertEquals(1, orderIds.size(), "all retries must return the same order");
        assertTrue(orderRepository.findByIdempotencyKey(key).isPresent());
        assertEquals(90, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable());
    }

    @Test
    void sameKey_twoDifferentItems_uniqueConstraintSettlesIt() throws Exception {
        final int threads = 10;
        final int total = 200;
        UUID itemA = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        UUID itemB = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        final String key = "junit-same-key-2items-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        AtomicInteger reused = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        AtomicInteger integrityViolations = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        Set<UUID> orderIds = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final UUID itemId = (i % 2 == 0) ? itemA : itemB;   // different row locks: only the unique constraint settles it
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    Order order = allocationFacade.allocate(itemId, 10, key);
                    orderIds.add(order.getOrderId());
                    successes.incrementAndGet();
                } catch (IdempotencyKeyReusedException e) {
                    reused.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    integrityViolations.incrementAndGet();
                    e.printStackTrace();
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

        int remainingA = inventoryItemRepository.findById(itemA).orElseThrow().getQuantityAvailable();
        int remainingB = inventoryItemRepository.findById(itemB).orElseThrow().getQuantityAvailable();

        assertEquals(0, integrityViolations.get(), "no DataIntegrityViolationException may escape");
        assertEquals(0, unexpected.get(), "no other error may occur");
        // Exactly one order wins. Threads that sent the winner's own payload (same item, same
        // quantity) are legitimate retries and get that order back; threads on the other item
        // reuse the key with a different payload and are rejected.
        assertEquals(1, orderIds.size(), "only one order may exist for the key");
        assertEquals(threads / 2, successes.get(), "only the winner's own retries may succeed");
        assertEquals(threads / 2, reused.get(), "every thread with the other item must be rejected");
        assertEquals(total - 10, remainingA + remainingB, "stock must be deducted exactly once");
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

    @Test
    void sameKey_differentQuantity_throwsReused_andChangesNothing() {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        String key = "junit-reuse-qty-" + UUID.randomUUID();

        Order first = allocationFacade.allocate(itemId, 10, key);

        assertThrows(IdempotencyKeyReusedException.class, () -> allocationFacade.allocate(itemId, 20, key));

        assertEquals(90, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable(),
                "the rejected reuse must not touch stock");
        assertEquals(1, orderRepository.findAll().stream().filter(o -> key.equals(o.getIdempotencyKey())).count(),
                "still exactly one order for the key");
        Order stored = orderRepository.findByIdempotencyKey(key).orElseThrow();
        assertEquals(first.getOrderId(), stored.getOrderId());
        assertEquals(10, stored.getQuantityRequested());
    }

    @Test
    void sameKey_differentItem_throwsReused_andChangesNothing() {
        UUID itemA = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        UUID itemB = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        String key = "junit-reuse-item-" + UUID.randomUUID();

        allocationFacade.allocate(itemA, 10, key);

        assertThrows(IdempotencyKeyReusedException.class, () -> allocationFacade.allocate(itemB, 10, key));

        assertEquals(90, inventoryItemRepository.findById(itemA).orElseThrow().getQuantityAvailable());
        assertEquals(100, inventoryItemRepository.findById(itemB).orElseThrow().getQuantityAvailable(),
                "the other item must be untouched");
        assertEquals(itemA, orderRepository.findByIdempotencyKey(key).orElseThrow().getItemId());
    }

    @Test
    void failedOrderReplay_samePayloadReturnsSameOrder_differentPayloadThrowsReused() {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(5)).getItemId();
        String key = "junit-reuse-failed-" + UUID.randomUUID();

        Order first = allocationFacade.allocate(itemId, 10, key);
        assertEquals(OrderStatus.FAILED, first.getStatus());

        Order replay = allocationFacade.allocate(itemId, 10, key);
        assertEquals(first.getOrderId(), replay.getOrderId());
        assertEquals(OrderStatus.FAILED, replay.getStatus());

        assertThrows(IdempotencyKeyReusedException.class, () -> allocationFacade.allocate(itemId, 3, key),
                "3 would fit in stock, but the key is bound to the quantity-10 payload");
        assertEquals(5, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable());
    }

    @Test
    void sameKey_tenThreadsDifferentQuantities_exactlyOneWins() throws Exception {
        final int threads = 10;
        UUID itemId = inventoryItemRepository.save(new InventoryItem(100)).getItemId();
        final String key = "junit-same-key-diff-qty-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        AtomicInteger reused = new AtomicInteger();
        AtomicInteger integrityViolations = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        List<Order> winners = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            final int quantity = i;                    // 1..10, every thread has a different payload
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    winners.add(allocationFacade.allocate(itemId, quantity, key));
                } catch (IdempotencyKeyReusedException e) {
                    reused.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    integrityViolations.incrementAndGet();
                    e.printStackTrace();
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

        assertEquals(0, integrityViolations.get(), "no DataIntegrityViolationException may escape");
        assertEquals(0, unexpected.get(), "no other error may occur");
        assertEquals(1, winners.size(), "exactly one thread may succeed");
        assertEquals(threads - 1, reused.get(), "all other threads must get IdempotencyKeyReusedException");

        int winnerQuantity = winners.get(0).getQuantityRequested();
        assertEquals(100 - winnerQuantity,
                inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable(),
                "stock must be deducted once, for the winner's quantity");
    }
}
