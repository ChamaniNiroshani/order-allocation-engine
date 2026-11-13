package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conditional-UPDATE strategy: hot-item race plus its two zero-row cases. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "allocation.locking-mode=ATOMIC_UPDATE")
class AtomicUpdateAllocationTest {

    @Autowired
    private OrderAllocationFacade facade;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Test
    void hotItem_neverOversells_andNoUnexpectedErrors() throws Exception {
        final int stock = 100;
        final int quantityPerOrder = 10;
        final int requests = 20;                       // 200 requested, only 100 available

        UUID itemId = inventoryItemRepository.save(new InventoryItem(stock)).getItemId();

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allocated = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            final String key = "junit-atomic-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    Order order = facade.allocate(itemId, quantityPerOrder, key);
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

        InventoryItem item = inventoryItemRepository.findById(itemId).orElseThrow();

        assertEquals(0, unexpected.get(), "no request may fail with an unexpected error");
        assertEquals(stock / quantityPerOrder, allocated.get());
        assertEquals(requests - allocated.get(), rejected.get());
        assertEquals(0, item.getQuantityAvailable(), "stock must be fully consumed and never negative");
        assertEquals((long) (stock / quantityPerOrder), item.getVersion().longValue(),
                "every successful decrement bumps the version");
    }

    @Test
    void insufficientStock_zeroRows_recordsFailedOrder_andLeavesStockUnchanged() {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(5)).getItemId();

        Order order = facade.allocate(itemId, 10, "junit-atomic-" + UUID.randomUUID());

        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertTrue(order.getFailureReason().contains("only 5 available"));
        assertEquals(5, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable());
    }

    @Test
    void unknownItem_zeroRows_isNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> facade.allocate(UUID.randomUUID(), 5, "junit-atomic-" + UUID.randomUUID()));
    }
}
