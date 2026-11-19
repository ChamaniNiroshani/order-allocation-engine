package com.chamani.order_allocation_engine.payment;

/** Outcome of a charge: APPROVED(paymentId) or DECLINED(reason). A timeout is an exception, not a result. */
public record PaymentResult(Status status, String paymentId, String reason) {

    public enum Status { APPROVED, DECLINED }

    public static PaymentResult approved(String paymentId) {
        return new PaymentResult(Status.APPROVED, paymentId, null);
    }

    public static PaymentResult declined(String reason) {
        return new PaymentResult(Status.DECLINED, null, reason);
    }

    public boolean isApproved() {
        return status == Status.APPROVED;
    }
}
