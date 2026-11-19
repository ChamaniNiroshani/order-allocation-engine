package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests (no Spring): every transition method from every status. */
class OrderStateMachineTest {

    private enum Action {
        RESERVE(OrderStatus.RESERVED, EnumSet.of(OrderStatus.PENDING), Order::reserve),
        MARK_PAID(OrderStatus.PAID, EnumSet.of(OrderStatus.RESERVED), o -> o.markPaid("pay-1")),
        CONFIRM(OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PAID), Order::confirm),
        CANCEL(OrderStatus.CANCELLED, EnumSet.of(OrderStatus.RESERVED, OrderStatus.PAID), o -> o.cancel("why")),
        EXPIRE(OrderStatus.EXPIRED, EnumSet.of(OrderStatus.RESERVED), Order::expire),
        MARK_FAILED(OrderStatus.FAILED, EnumSet.of(OrderStatus.PENDING), o -> o.markFailed("no stock")),
        MARK_ALLOCATED(OrderStatus.ALLOCATED, EnumSet.of(OrderStatus.PENDING), Order::markAllocated);

        final OrderStatus target;
        final Set<OrderStatus> legalFrom;   // written out by hand, independent of OrderStatus.ALLOWED
        final Consumer<Order> run;

        Action(OrderStatus target, Set<OrderStatus> legalFrom, Consumer<Order> run) {
            this.target = target;
            this.legalFrom = legalFrom;
            this.run = run;
        }
    }

    /** An order in the given status, reached by legal moves where possible. */
    private static Order orderIn(OrderStatus status) throws Exception {
        Order order = new Order(UUID.randomUUID(), 5, "key-" + UUID.randomUUID());
        switch (status) {
            case PENDING -> { }
            case RESERVED -> order.reserve();
            case PAID -> { order.reserve(); order.markPaid("pay-0"); }
            case CONFIRMED -> { order.reserve(); order.markPaid("pay-0"); order.confirm(); }
            case CANCELLED -> { order.reserve(); order.cancel("c"); }
            case EXPIRED -> { order.reserve(); order.expire(); }
            case FAILED -> order.markFailed("f");
            case ALLOCATED -> order.markAllocated();
            default -> {                      // no legal way in (PARTIALLY_ALLOCATED, ROLLED_BACK): force it
                Field f = Order.class.getDeclaredField("status");
                f.setAccessible(true);
                f.set(order, status);
            }
        }
        assertEquals(status, order.getStatus());
        return order;
    }

    static Stream<Arguments> everyStatusAndAction() {
        List<Arguments> all = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (Action action : Action.values()) {
                all.add(Arguments.of(from, action));
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyStatusAndAction")
    void transition_isLegalOnlyFromTheAllowedStates(OrderStatus from, Action action) throws Exception {
        Order order = orderIn(from);

        if (action.legalFrom.contains(from)) {
            action.run.accept(order);
            assertEquals(action.target, order.getStatus());
        } else {
            assertThrows(IllegalStateException.class, () -> action.run.accept(order));
            assertEquals(from, order.getStatus(), "a rejected transition must not change the status");
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyStatusPair")
    void canTransitionTo_matchesTheTable(OrderStatus from, OrderStatus to) {
        boolean expected = false;
        for (Action a : Action.values()) {
            if (a.target == to && a.legalFrom.contains(from)) {
                expected = true;
            }
        }
        assertEquals(expected, from.canTransitionTo(to));
    }

    static Stream<Arguments> everyStatusPair() {
        List<Arguments> all = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                all.add(Arguments.of(from, to));
            }
        }
        return all.stream();
    }

    @Test
    void terminalStates_haveNoWayOut() {
        for (OrderStatus terminal : EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderStatus.EXPIRED,
                OrderStatus.FAILED, OrderStatus.ALLOCATED, OrderStatus.PARTIALLY_ALLOCATED, OrderStatus.ROLLED_BACK)) {
            for (OrderStatus to : OrderStatus.values()) {
                assertFalse(terminal.canTransitionTo(to), terminal + " -> " + to);
            }
        }
    }

    @Test
    void happyPath_setsTheSagaFields() throws Exception {
        Order order = orderIn(OrderStatus.PENDING);
        assertNull(order.getReservedAt());

        order.reserve();
        assertNotNull(order.getReservedAt());

        order.markPaid("pay-42");
        assertEquals("pay-42", order.getPaymentId());

        order.confirm();
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        assertTrue(!order.getUpdatedAt().isBefore(order.getReservedAt()));
    }

    @Test
    void cancelAndExpire_recordAReason() throws Exception {
        Order cancelled = orderIn(OrderStatus.RESERVED);
        cancelled.cancel("Payment declined");
        assertEquals("Payment declined", cancelled.getFailureReason());

        Order expired = orderIn(OrderStatus.RESERVED);
        expired.expire();
        assertEquals("Reservation expired", expired.getFailureReason());
    }
}
