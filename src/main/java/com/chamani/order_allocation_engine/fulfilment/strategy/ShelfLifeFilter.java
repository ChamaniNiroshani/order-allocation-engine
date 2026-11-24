package com.chamani.order_allocation_engine.fulfilment.strategy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The candidate filter every strategy applies first, so no strategy can ship stock that is expired or about to be:
 * batches with no stock are dropped, expired batches are dropped, and so are batches whose remaining shelf life is below
 * {@code allocation.min-shelf-life-days} (default 2). A batch with no expiry date never expires. "Today" is the injected
 * Clock's date (UTC in the application), counted in whole days: a batch expiring in 2 days has a remaining shelf life of 2.
 */
@Component
public class ShelfLifeFilter {

    private final Clock clock;
    private final int minShelfLifeDays;

    public ShelfLifeFilter(Clock clock, @Value("${allocation.min-shelf-life-days:2}") int minShelfLifeDays) {
        if (minShelfLifeDays < 0) {
            throw new IllegalArgumentException("allocation.min-shelf-life-days must not be negative, was " + minShelfLifeDays);
        }
        this.clock = clock;
        this.minShelfLifeDays = minShelfLifeDays;
    }

    public List<BatchCandidate> apply(List<BatchCandidate> candidates) {
        LocalDate today = LocalDate.now(clock);
        return candidates.stream().filter(c -> usable(c, today)).toList();
    }

    private boolean usable(BatchCandidate candidate, LocalDate today) {
        if (candidate.quantityAvailable() <= 0) {
            return false;
        }
        if (candidate.expiryDate() == null) {
            return true;
        }
        return ChronoUnit.DAYS.between(today, candidate.expiryDate()) >= minShelfLifeDays;
    }
}
