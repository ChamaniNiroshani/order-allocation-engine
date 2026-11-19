package com.chamani.order_allocation_engine.metrics;

import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the reservations.active gauge fresh. Prometheus scrapes every few seconds, from every viewer's
 * dashboard too, so the count is read on a schedule (one cheap indexed COUNT) and the scrape only reads
 * the cached number.
 *
 * Like the expiry job: the scheduled entry point does nothing when disabled (tests call {@link #refresh()}).
 */
@Component
public class ActiveReservationsRefresher {

    private static final Logger log = LoggerFactory.getLogger(ActiveReservationsRefresher.class);

    private final OrderRepository orderRepository;
    private final AllocationMetrics metrics;
    private final boolean enabled;

    public ActiveReservationsRefresher(OrderRepository orderRepository, AllocationMetrics metrics,
                                       @Value("${metrics.reservations.enabled:true}") boolean enabled) {
        this.orderRepository = orderRepository;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${metrics.reservations.refresh-interval:5s}")
    public void scheduledRefresh() {
        if (!enabled) {
            return;
        }
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Could not refresh the active reservations gauge, keeping the last value", e);
        }
    }

    /** @return the number of RESERVED orders, now also what the gauge reports */
    public long refresh() {
        long reserved = orderRepository.countByStatus(OrderStatus.RESERVED);
        metrics.setActiveReservations(reserved);
        return reserved;
    }
}
