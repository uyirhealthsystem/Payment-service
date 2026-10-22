package com.uyirhealthapp.payment_service.common;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/** Thrown from services; the GlobalExceptionHandler turns it into an HTTP error response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    /** Sent as Retry-After so clients back off; null for none. */
    private final Duration retryAfter;

    public ApiException(HttpStatus status, String message, Duration retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException serviceUnavailable(String message, Duration retryAfter) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message, retryAfter);
    }
}
