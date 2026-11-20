package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.concurrency.AllocationProperties;
import com.chamani.order_allocation_engine.concurrency.LockingMode;
import com.chamani.order_allocation_engine.concurrency.OptimisticRetryExecutor;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.service.AllocationService;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import com.chamani.order_allocation_engine.service.RequestHash;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAllocationFacadeTest {

    private static final String KEY = "key-1";

    @Mock
    private AllocationService allocationService;

    @Mock
    private OrderRepository orderRepository;

    @Spy
    private OptimisticRetryExecutor optimisticRetry = new OptimisticRetryExecutor(new SimpleMeterRegistry());

    @Spy
    private AllocationMetrics metrics = new AllocationMetrics(new SimpleMeterRegistry(), new AllocationProperties(LockingMode.PESSIMISTIC));

    @InjectMocks
    private OrderAllocationFacade facade;

    @Test
    void onIdempotencyConflict_returnsTheStoredOrder() {
        UUID itemId = UUID.randomUUID();
        Order stored = new Order(itemId, 10, KEY);
        when(allocationService.allocate(itemId, 10, KEY)).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(stored));

        assertSame(stored, facade.allocate(itemId, 10, KEY));
    }

    @Test
    void onConflictWithNoStoredOrder_rethrowsTheOriginalException() {
        UUID itemId = UUID.randomUUID();
        DataIntegrityViolationException original = new DataIntegrityViolationException("some other violation");
        when(allocationService.allocate(itemId, 10, KEY)).thenThrow(original);
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class, () -> facade.allocate(itemId, 10, KEY));
        assertSame(original, thrown);
    }

    @Test
    void onConflictWithDifferentHash_throwsIdempotencyKeyReused() {
        UUID itemId = UUID.randomUUID();
        Order stored = new Order(itemId, 10, KEY, RequestHash.of(itemId, 10));
        when(allocationService.allocate(itemId, 20, KEY)).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(stored));

        assertThrows(IdempotencyKeyReusedException.class, () -> facade.allocate(itemId, 20, KEY));
    }

    @Test
    void onConflictWithSameHash_returnsTheStoredOrder() {
        UUID itemId = UUID.randomUUID();
        Order stored = new Order(itemId, 10, KEY, RequestHash.of(itemId, 10));
        when(allocationService.allocate(itemId, 10, KEY)).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(stored));

        assertSame(stored, facade.allocate(itemId, 10, KEY));
    }
}
