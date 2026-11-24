package com.chamani.order_allocation_engine.fulfilment;

/** The request named a strategy that does not exist. Answered as 400. */
public class InvalidStrategyException extends RuntimeException {

    public InvalidStrategyException(String message) {
        super(message);
    }
}
