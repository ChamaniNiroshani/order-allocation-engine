package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two warehouses, one batch of the SAME sku in each. Orders delivered next to warehouse A prefer A first, orders delivered next to
 * B prefer B first, and every order needs stock from BOTH. A transaction that locked batches in its own preference order would
 * lock A then B while another locks B then A: a textbook deadlock. FulfilmentService locks in batch-id order for everybody, so
 * this must never produce "deadlock detected" (SQLSTATE 40P01). 50 rounds, all orders of a round released at the same instant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SplitDeadlockTest {

    private static final int ROUNDS = 50;
    private static final int ORDERS_PER_KIND = 2;
    private static final int STOCK_PER_WAREHOUSE = 100;
    private static final int QUANTITY = 120;      // more than one warehouse holds: every order needs both

    @Autowired FulfilmentFacade facade;
    @Autowired WarehouseRepository warehouses;
    @Autowired InventoryBatchRepository batches;
    @Autowired FulfilmentLineRepository lines;

    @Test
    void opposingPreferences_neverDeadlock_neverOversell_andStockAddsUp() throws Exception {
        Warehouse a = warehouses.save(new Warehouse("A", new BigDecimal("6.000000"), new BigDecimal("80.000000")));
        Warehouse b = warehouses.save(new Warehouse("B", new BigDecimal("7.000000"), new BigDecimal("80.000000")));
        ExecutorService pool = Executors.newFixedThreadPool(2 * ORDERS_PER_KIND);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String sku = "DEADLOCK-" + round + "-" + UUID.randomUUID();
                InventoryBatch batchA = batches.save(new InventoryBatch(sku, a.getId(), STOCK_PER_WAREHOUSE, LocalDate.now().plusDays(30)));
                InventoryBatch batchB = batches.save(new InventoryBatch(sku, b.getId(), STOCK_PER_WAREHOUSE, LocalDate.now().plusDays(30)));

                CountDownLatch start = new CountDownLatch(1);
                List<Future<FulfilmentDtos.FulfilmentResponse>> futures = new ArrayList<>();
                for (int i = 0; i < ORDERS_PER_KIND; i++) {
                    futures.add(pool.submit(order(start, sku, "6.0", "80.0")));   // lives next to A: prefers A, then B
                    futures.add(pool.submit(order(start, sku, "7.0", "80.0")));   // lives next to B: prefers B, then A
                }
                start.countDown();

                int allocated = 0;
                for (Future<FulfilmentDtos.FulfilmentResponse> future : futures) {
                    // get() rethrows any exception, including a deadlock-victim's CannotAcquireLockException / SQLSTATE 40P01
                    FulfilmentDtos.FulfilmentResponse response = future.get();
                    if (response.status() == FulfilmentStatus.ALLOCATED) {
                        allocated++;
                        assertThat(response.lines()).extracting(FulfilmentDtos.LineResponse::warehouseId)
                                .containsExactlyInAnyOrder(a.getId(), b.getId());
                    }
                }

                int remainingA = batches.findById(batchA.getId()).orElseThrow().getQtyAvailable();
                int remainingB = batches.findById(batchB.getId()).orElseThrow().getQtyAvailable();
                assertThat(remainingA).isBetween(0, STOCK_PER_WAREHOUSE);
                assertThat(remainingB).isBetween(0, STOCK_PER_WAREHOUSE);
                // 200 in stock, 120 per order: exactly one order fits, the other three are rejected
                assertThat(allocated).as("round %d", round).isEqualTo(1);
                // invariant: what left the shelves is exactly what the lines say
                assertThat(2 * STOCK_PER_WAREHOUSE - remainingA - remainingB).isEqualTo(allocated * QUANTITY);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<FulfilmentDtos.FulfilmentResponse> order(CountDownLatch start, String sku, String lat, String lon) {
        return () -> {
            start.await();
            return facade.fulfil(new FulfilmentService.Command(sku, QUANTITY, new BigDecimal(lat), new BigDecimal(lon),
                    CustomerTier.STANDARD, StrategyType.SPLIT, "dl-" + UUID.randomUUID()));
        };
    }
}
