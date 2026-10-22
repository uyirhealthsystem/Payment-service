package com.uyirhealthapp.payment_service.gateway;

/** The provider refused the details sent (invalid PAN, IFSC, duplicate e-mail, ...); the message says why. */
public class DetailsRejectedException extends GatewayException {

    public DetailsRejectedException(String message, Throwable cause) {
        super(message, false, cause);
    }
}
