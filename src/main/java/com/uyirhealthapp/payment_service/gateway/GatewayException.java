package com.uyirhealthapp.payment_service.gateway;

/**
 * The provider could not be reached, or refused the request. retryable: a network/5xx problem where the same
 * call may succeed later; otherwise the provider rejected it (bad amount, configuration, ...).
 */
public class GatewayException extends RuntimeException {

    private final boolean retryable;

    public GatewayException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public GatewayException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
