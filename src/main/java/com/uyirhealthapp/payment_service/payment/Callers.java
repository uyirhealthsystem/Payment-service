package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Who is calling: the payer themselves, or an admin who may see and refund any payment. */
@Component
public class Callers {

    private final PaymentProperties.Security security;

    public Callers(PaymentProperties props) {
        this.security = props.security();
    }

    public boolean isAdmin(Jwt jwt) {
        return security.adminRoles().contains(jwt.getClaimAsString(security.roleClaim()));
    }

    public boolean mayAccess(Jwt jwt, Payment p) {
        return isAdmin(jwt) || p.getPayerId().equals(jwt.getSubject());
    }
}
