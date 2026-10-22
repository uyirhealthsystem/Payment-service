package com.uyirhealthapp.payment_service.payment;

/**
 * The status every module sees. Kept to these three on purpose: the modules deserialize it into their own enum
 * (ambulance: PENDING | PAID | FAILED), so adding a value here is a breaking change for them. Refunds are
 * reported separately (refundedAmount).
 *
 * PENDING --paid--> PAID
 * PENDING --failed--> FAILED --new checkout--> PENDING   (the user can simply try again)
 */
public enum PaymentStatus {
    PENDING, PAID, FAILED
}
