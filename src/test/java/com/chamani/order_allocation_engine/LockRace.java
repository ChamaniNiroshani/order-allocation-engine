package com.chamani.order_allocation_engine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forces a same-key race instead of hoping for one. Starting threads together does not prove anything: one request may
 * commit before the other looks its key up, which is only an ordinary replay. Here a third transaction holds the stock
 * row(s), and is released only once Postgres reports every request as waiting for a lock. All of them have then passed
 * the "is this key already stored?" check and none has committed.
 */
final class LockRace {

    private LockRace() {
    }

    static <T> List<T> run(PlatformTransactionManager transactionManager, JdbcTemplate jdbc, String lockSql, Object lockArg,
                           int requests, Callable<T> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests + 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForList(lockSql, lockArg);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "the stock row could not be locked");

            List<Future<T>> running = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                running.add(pool.submit(request));
            }
            awaitLockWaiters(jdbc, requests);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            List<T> results = new ArrayList<>();
            for (Future<T> future : running) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitLockWaiters(JdbcTemplate jdbc, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("only " + waiting + " of " + expected + " requests reached a lock wait");
    }
}
