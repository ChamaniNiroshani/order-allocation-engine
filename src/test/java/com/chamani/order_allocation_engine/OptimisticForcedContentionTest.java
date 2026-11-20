package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.exception.StockContentionException;
import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hot-item test in the shared suite ALLOWS contention but cannot force it. This one does: with a single attempt there
 * are no retries, so when concurrent requests read the same item version, every one that loses the race must surface as
 * StockContentionException. It proves that path really runs, and that it never costs correctness.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "allocation.locking-mode=OPTIMISTIC",
        "allocation.optimistic.max-attempts=1"
})
class OptimisticForcedContentionTest {

    @Autowired
    private OrderAllocationFacade allocationFacade;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void singleAttempt_hotItem_contentionHappens_butNeverOversells() throws Exception {
        final int stock = 1_000;                       // plenty: every loss here is a lost race, never a lack of stock
        final int quantityPerOrder = 1;
        final int requests = 40;

        UUID itemId = inventoryItemRepository.save(new InventoryItem(stock)).getItemId();

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allocated = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger contention = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            final String key = "junit-forced-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    Order order = allocationFacade.allocate(itemId, quantityPerOrder, key);
                    if (order.getStatus() == OrderStatus.ALLOCATED) {
                        allocated.incrementAndGet();
                    } else if (order.getStatus() == OrderStatus.FAILED) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (StockContentionException e) {
                    contention.incrementAndGet();
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
        int allocatedInDb = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity_allocated), 0) FROM allocation WHERE item_id = ?", Integer.class, itemId);

        assertEquals(0, unexpected.get(), "no request may fail with an unexpected error");
        assertTrue(contention.get() > 0, "with one attempt and " + requests + " simultaneous requests the contention path must run");
        assertEquals(requests, allocated.get() + rejected.get() + contention.get(), "every request has exactly one outcome");
        assertEquals(0, rejected.get(), "there is plenty of stock, so nothing is rejected for lack of it");
        assertTrue(allocated.get() >= 1, "at least the winner of the race is allocated");
        assertTrue(remaining >= 0, "never oversell");
        assertEquals(allocated.get() * quantityPerOrder, allocatedInDb, "allocation rows match the ALLOCATED responses");
        assertEquals(stock, allocatedInDb + remaining, "sum(allocated) + remaining == initial stock");
    }
}
