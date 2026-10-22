package com.uyirhealthapp.payment_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Trusts the identity the api-gateway already verified, so requests through the gateway never need a JWKS.
 * <p>
 * The gateway verifies the JWT, strips any X-User-* / X-Gateway-Secret a client sent, and forwards
 * X-User-Id (= sub), X-User-Role and X-Gateway-Secret. When the secret matches, the request is authenticated
 * from those headers. Without a (matching) secret nothing happens here and the normal JWT check runs, so a
 * caller that skips the gateway must present a valid token and cannot act as anyone by setting the headers.
 * <p>
 * With trustIdentityHeaders (development only) the secret is not checked: a request with X-User-Id is believed.
 * <p>
 * The principal is a {@link Jwt} built from the gateway's claims (sub, role), so controllers read it the same
 * way either way.
 */
@Slf4j
public class GatewayAuthFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Gateway-Secret";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";

    private final byte[] secret;
    private final boolean trustWithoutSecret;
    private final String roleClaim;

    public GatewayAuthFilter(String secret, boolean trustWithoutSecret, String roleClaim) {
        this.secret = StringUtils.hasText(secret) ? secret.getBytes(StandardCharsets.UTF_8) : null;
        this.trustWithoutSecret = trustWithoutSecret;
        this.roleClaim = roleClaim;
        if (trustWithoutSecret) {
            log.warn("payment.security.trust-identity-headers is ON: X-User-Id / X-User-Role are believed without "
                    + "a token or gateway secret. Development only - never expose this instance.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(SECRET_HEADER);
        if (trustWithoutSecret) {
            authenticate(request);
        } else if (presented != null && secret != null) {
            if (MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8))) {
                authenticate(request);
            } else {
                log.warn("Ignoring {} that does not match (request from {})", SECRET_HEADER, request.getRemoteAddr());
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request) {
        String userId = request.getHeader(USER_ID_HEADER);
        if (!StringUtils.hasText(userId)) {
            return; // no identity asserted: the normal bearer-token check applies
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication(userId, request.getHeader(ROLE_HEADER), roleClaim));
        SecurityContextHolder.setContext(context);
    }

    /** The same authentication a verified token gives: principal = Jwt(sub, role), authority ROLE_&lt;role&gt;. */
    static JwtAuthenticationToken authentication(String userId, String role, String roleClaim) {
        Jwt.Builder jwt = Jwt.withTokenValue("gateway").header("alg", "none").subject(userId.trim());
        List<SimpleGrantedAuthority> authorities = List.of();
        if (StringUtils.hasText(role)) {
            jwt.claim(roleClaim, role.trim());
            authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.trim()));
        }
        return new JwtAuthenticationToken(jwt.build(), authorities);
    }
}
