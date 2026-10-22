package com.uyirhealthapp.payment_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * If the auth service's JWKS cannot be fetched, Spring Security throws an AuthenticationServiceException, which
 * would surface as a confusing 500. This turns it into a clear 503: the token itself may be fine, we just
 * cannot verify it right now.
 */
@Slf4j
public class AuthUnavailableFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException | jakarta.servlet.ServletException e) {
            AuthenticationServiceException ase = find(e);
            if (ase == null) {
                throw e;
            }
            log.error("Cannot verify token, auth service unreachable: {}", ase.getMessage());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":503,\"title\":\"Service Unavailable\","
                    + "\"detail\":\"Cannot verify your token: the auth service (JWKS) is unreachable. Try again shortly.\"}");
        }
    }

    private static AuthenticationServiceException find(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof AuthenticationServiceException a) {
                return a;
            }
        }
        return null;
    }
}
