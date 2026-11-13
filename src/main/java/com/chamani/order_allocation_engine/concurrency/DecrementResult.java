package com.chamani.order_allocation_engine.concurrency;

/**
 * Outcome of a stock decrement. {@code available} is the quantity left after a successful
 * decrement, or the quantity that was available when the request was rejected.
 */
public record DecrementResult(Outcome outcome, int available) {

    public enum Outcome { DECREMENTED, INSUFFICIENT }

    public static DecrementResult decremented(int remaining) {
        return new DecrementResult(Outcome.DECREMENTED, remaining);
    }

    public static DecrementResult insufficient(int available) {
        return new DecrementResult(Outcome.INSUFFICIENT, available);
    }

    public boolean isDecremented() {
        return outcome == Outcome.DECREMENTED;
    }
}
