package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.repository.TransactionLogRepository;
import com.chamani.order_allocation_engine.saga.CheckoutProperties;
import com.chamani.order_allocation_engine.saga.CheckoutSteps;
import com.chamani.order_allocation_engine.saga.ReservationExpiryJob;
import com.chamani.order_allocation_engine.saga.ReservationExpiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The expiry job against a real Postgres. checkout.expiry.enabled=false (src/test/resources/config), so only these calls run it. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationExpiryTest {

    @Autowired
    private ReservationExpiryJob job;

    @Autowired
    private ReservationExpiryService expiryService;

    @Autowired
    private CheckoutSteps steps;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionLogRepository transactionLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    /** Start every test with nothing stale in the shared database, so the job's counts are exact. */
    @BeforeEach
    void drainLeftovers() {
        job.expireStale();
        job.recoverPaid();
    }

    private UUID newItem(int available) {
        return inventoryItemRepository.save(new InventoryItem(available)).getItemId();
    }

    private UUID reserve(UUID itemId, int quantity) {
        CheckoutSteps.Reservation r = steps.reserve(itemId, quantity, "expiry-" + UUID.randomUUID(), "hash");
        assertEquals(OrderStatus.RESERVED, r.order().getStatus());
        return r.order().getOrderId();
    }

    private UUID reservedAndPaid(UUID itemId, int quantity) {
        UUID orderId = reserve(itemId, quantity);
        steps.recordPayment(orderId, "pay-" + UUID.randomUUID());
        return orderId;
    }

    private void backdate(UUID orderId, int minutesAgo) {
        jdbc.update("UPDATE orders SET reserved_at = now() - make_interval(mins => ?) WHERE order_id = ?", minutesAgo, orderId);
    }

    private Order order(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private void assertStock(UUID itemId, int available, int reserved) {
        InventoryItem item = inventoryItemRepository.findById(itemId).orElseThrow();
        assertEquals(available, item.getQuantityAvailable(), "available");
        assertEquals(reserved, item.getQuantityReserved(), "reserved");
    }

    private List<String> actions(UUID orderId) {
        return transactionLogRepository.findByEntityIdOrderByTimestampAsc(orderId).stream().map(l -> l.getAction()).toList();
    }

    // ---- expiring stale reservations --------------------------------------------------------------------

    @Test
    void staleReservation_isExpired_stockReturns_andIsAudited() {
        UUID itemId = newItem(10);
        UUID orderId = reserve(itemId, 4);
        assertStock(itemId, 6, 4);
        backdate(orderId, 10);

        int expired = job.expireStale();

        assertEquals(1, expired);
        assertEquals(OrderStatus.EXPIRED, order(orderId).getStatus());
        assertEquals("Reservation expired", order(orderId).getFailureReason());
        assertStock(itemId, 10, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED", "REFUND_REQUESTED"), actions(orderId));
    }

    @Test
    void freshReservation_isLeftAlone() {
        UUID itemId = newItem(10);
        UUID orderId = reserve(itemId, 4);
        backdate(orderId, 2);                                   // TTL is 5 minutes

        assertEquals(0, job.expireStale());

        assertEquals(OrderStatus.RESERVED, order(orderId).getStatus());
        assertStock(itemId, 6, 4);
    }

    @Test
    void ordersThatAreNotReserved_areNeverExpired_evenWhenOld() {
        UUID itemId = newItem(10);
        UUID cancelled = reserve(itemId, 3);
        steps.compensate(cancelled, "declined");
        UUID confirmed = reservedAndPaid(itemId, 2);
        steps.confirm(confirmed);
        backdate(cancelled, 30);
        backdate(confirmed, 30);

        assertEquals(0, job.expireStale());

        assertEquals(OrderStatus.CANCELLED, order(cancelled).getStatus());
        assertEquals(OrderStatus.CONFIRMED, order(confirmed).getStatus());
        assertStock(itemId, 8, 0);
    }

    @Test
    void runningTheJobTwice_expiresOnce_andReleasesOnce() {
        UUID itemId = newItem(10);
        UUID orderId = reserve(itemId, 4);
        backdate(orderId, 10);

        assertEquals(1, job.expireStale());
        assertEquals(0, job.expireStale());

        assertStock(itemId, 10, 0);
        assertEquals(3, actions(orderId).size());              // reserved, expired, refund requested
    }

    @Test
    void batches_areBoundedByTheBatchSize_oldestFirst() {
        UUID itemId = newItem(10);
        UUID oldest = reserve(itemId, 1);
        UUID middle = reserve(itemId, 1);
        UUID newest = reserve(itemId, 1);
        backdate(oldest, 30);
        backdate(middle, 20);
        backdate(newest, 10);
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));

        assertEquals(2, expiryService.expireBatch(cutoff, 2).size(), "a batch of 2");
        assertEquals(OrderStatus.EXPIRED, order(oldest).getStatus());
        assertEquals(OrderStatus.EXPIRED, order(middle).getStatus());
        assertEquals(OrderStatus.RESERVED, order(newest).getStatus(), "the newest waits for the next batch");

        assertEquals(1, expiryService.expireBatch(cutoff, 2).size());
        assertEquals(OrderStatus.EXPIRED, order(newest).getStatus());
        assertStock(itemId, 10, 0);
    }

    @Test
    void theJob_drainsEverythingStale_acrossSeveralBatches() {
        UUID itemId = newItem(300);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 230; i++) {                         // more than the default batch of 100
            UUID id = reserve(itemId, 1);
            backdate(id, 15);
            ids.add(id);
        }

        assertEquals(230, job.expireStale());

        assertStock(itemId, 300, 0);
        for (UUID id : ids) {
            assertEquals(OrderStatus.EXPIRED, order(id).getStatus());
        }
    }

    @Test
    void anOrderAnotherTransactionHolds_isSkipped_notWaitedFor() throws Exception {
        UUID itemId = newItem(10);
        UUID held = reserve(itemId, 3);
        UUID free = reserve(itemId, 2);
        backdate(held, 10);
        backdate(free, 10);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            orderRepository.findByIdForUpdate(held).orElseThrow();       // e.g. a saga step mid-payment
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));

        long start = System.nanoTime();
        int expired = job.expireStale();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, expired, "only the free order");
        assertTrue(elapsedMs < 5_000, "the job must not wait for the locked row, took " + elapsedMs + " ms");
        assertEquals(OrderStatus.RESERVED, order(held).getStatus());
        assertEquals(OrderStatus.EXPIRED, order(free).getStatus());

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, job.expireStale(), "the next run picks up the order that was locked");
        assertEquals(OrderStatus.EXPIRED, order(held).getStatus());
        assertStock(itemId, 10, 0);
    }

    @Test
    void twoJobsAtOnce_neverShareAnOrder_stockIsReleasedExactlyOnce() throws Exception {
        UUID itemId = newItem(40);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID id = reserve(itemId, 1);
            backdate(id, 10);
            ids.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(pool.submit(() -> {
                go.await();
                return job.expireStale();
            }));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> run : runs) {
            total += run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(40, total, "every order expired by exactly one of the two jobs");
        assertStock(itemId, 40, 0);
        for (UUID id : ids) {
            assertEquals(3, actions(id).size(), "reserved + expired + refund requested, never expired twice");
        }
    }

    @Test
    void aPaymentArrivingAfterTheExpiry_isTooLate_andAConfirmIsRefused() {
        UUID itemId = newItem(10);
        UUID orderId = reserve(itemId, 4);
        backdate(orderId, 10);
        job.expireStale();

        assertEquals(CheckoutSteps.PaymentRecording.TOO_LATE, steps.recordPayment(orderId, "pay-late"));
        assertThrows(IllegalStateException.class, () -> steps.confirm(orderId));

        assertStock(itemId, 10, 0);
    }

    // ---- crash recovery ---------------------------------------------------------------------------------

    @Test
    void paidButNeverConfirmed_isConfirmedByTheJob() {
        UUID itemId = newItem(10);
        UUID orderId = reservedAndPaid(itemId, 4);            // crash right here: charged, not confirmed
        assertStock(itemId, 6, 4);
        backdate(orderId, 10);

        assertEquals(0, job.expireStale(), "a PAID order is never expired");
        assertEquals(1, job.recoverPaid());

        assertEquals(OrderStatus.CONFIRMED, order(orderId).getStatus());
        assertStock(itemId, 6, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->PAID", "PAID->CONFIRMED"), actions(orderId));
    }

    @Test
    void aFreshPaidOrder_isLeftForTheSagaToConfirm() {
        UUID itemId = newItem(10);
        UUID orderId = reservedAndPaid(itemId, 4);

        assertEquals(0, job.recoverPaid());

        assertEquals(OrderStatus.PAID, order(orderId).getStatus());
        assertStock(itemId, 6, 4);
    }

    @Test
    void ifTheJobConfirmsFirst_theSagasOwnConfirmIsANoOp() {
        UUID itemId = newItem(10);
        UUID orderId = reservedAndPaid(itemId, 4);
        backdate(orderId, 10);
        job.recoverPaid();

        steps.confirm(orderId);                                // the slow saga finally gets there

        assertEquals(OrderStatus.CONFIRMED, order(orderId).getStatus());
        assertStock(itemId, 6, 0);
        assertEquals(3, actions(orderId).size());
    }

    // ---- configuration ----------------------------------------------------------------------------------

    @Test
    void theScheduledEntryPoint_doesNothingWhenDisabled() {
        UUID itemId = newItem(10);
        UUID orderId = reserve(itemId, 4);
        backdate(orderId, 10);

        job.scheduledRun();                                    // checkout.expiry.enabled=false in tests

        assertEquals(OrderStatus.RESERVED, order(orderId).getStatus());
        job.expireStale();
        assertEquals(OrderStatus.EXPIRED, order(orderId).getStatus());
    }

    @Test
    void properties_haveTheDocumentedDefaults() {
        CheckoutProperties p = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("checkout", CheckoutProperties.class);

        assertEquals(Duration.ofMinutes(5), p.reservationTtl());
        assertTrue(p.expiry().enabled());
        assertEquals(Duration.ofSeconds(30), p.expiry().interval());
        assertEquals(100, p.expiry().batchSize());
    }

    @Test
    void properties_bindFromKebabCaseKeys() {
        CheckoutProperties p = new Binder(new MapConfigurationPropertySource(Map.of(
                "checkout.reservation-ttl", "90s",
                "checkout.expiry.enabled", "false",
                "checkout.expiry.interval", "10s",
                "checkout.expiry.batch-size", "25")))
                .bindOrCreate("checkout", CheckoutProperties.class);

        assertEquals(Duration.ofSeconds(90), p.reservationTtl());
        assertEquals(false, p.expiry().enabled());
        assertEquals(Duration.ofSeconds(10), p.expiry().interval());
        assertEquals(25, p.expiry().batchSize());
    }

    @Test
    void properties_rejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutProperties(Duration.ZERO, new CheckoutProperties.Expiry(true, Duration.ofSeconds(1), 10)));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutProperties.Expiry(true, Duration.ofSeconds(1), 0));
    }
}
