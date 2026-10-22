package com.uyirhealthapp.payment_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * This service does NOT issue tokens. Identity ("sub" = user id, "role" = PATIENT | DOCTOR | ADMIN | ...) comes from:
 * <ul>
 *   <li>the api-gateway: with payment.security.gateway-secret set, a request carrying the matching
 *       X-Gateway-Secret is authenticated from X-User-Id / X-User-Role ({@link GatewayAuthFilter}), no JWT check;</li>
 *   <li>otherwise a bearer JWT, verified against payment.security.jwks-uri / public-key-location
 *       (callers that skip the gateway).</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The api-gateway forwards /api/payments/** to this service unchanged. */
    public static final String API = "/api/payments";

    /** Public at the gateway too: provider callbacks carry no user token, they are verified by signature. */
    public static final String WEBHOOKS = API + "/webhooks";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                            JwtAuthenticationConverter converter,
                                            PaymentProperties props) throws Exception {
        String[] admins = props.security().adminRoles().toArray(String[]::new);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        // scraped by Prometheus inside the cluster; keep it off the public gateway route
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // verified by the provider's HMAC signature in the webhook controller
                        .requestMatchers(WEBHOOKS + "/**").permitAll()
                        .requestMatchers(API + "/*/refunds").hasAnyRole(admins)
                        .anyRequest().authenticated())
                .addFilterBefore(new AuthUnavailableFilter(), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(skipWhenAuthenticated())
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)));
        PaymentProperties.Security sec = props.security();
        if (sec.trustIdentityHeaders() || StringUtils.hasText(sec.gatewaySecret())) {
            http.addFilterBefore(new GatewayAuthFilter(sec.gatewaySecret(), sec.trustIdentityHeaders(),
                    sec.roleClaim()), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    /** A request the gateway already vouched for is not re-verified (the gateway forwards the bearer token too). */
    private static BearerTokenResolver skipWhenAuthenticated() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return request -> SecurityContextHolder.getContext().getAuthentication() != null
                ? null : resolver.resolve(request);
    }

    /** Maps the "role" claim (e.g. "ADMIN") to the Spring authority ROLE_ADMIN. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(PaymentProperties props) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(props.security().roleClaim());
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(PaymentProperties props) throws Exception {
        PaymentProperties.Security sec = props.security();
        boolean headersAccepted = sec.trustIdentityHeaders() || StringUtils.hasText(sec.gatewaySecret());
        if (!sec.hasKeySource()) {
            // gateway-only deployment: identity comes from the gateway's headers, bearer tokens cannot be verified
            return rejectAllTokens();
        }
        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(sec.publicKeyLocation())) {
            // local testing only: trust a fixed public key instead of the identity provider's JWKS
            Resource key = new DefaultResourceLoader().getResource(sec.publicKeyLocation());
            if (!key.exists()) {
                if (headersAccepted) {
                    log.warn("{} does not exist: bearer tokens are rejected, X-User-* headers still work. "
                            + "Run \"node scripts/dev-jwt.mjs patient-42 PATIENT\" to create it.", sec.publicKeyLocation());
                    return rejectAllTokens();
                }
                throw new IllegalStateException("payment.security.public-key-location " + sec.publicKeyLocation()
                        + " does not exist. Locally, run \"node scripts/dev-jwt.mjs patient-42 PATIENT\" once.");
            }
            decoder = NimbusJwtDecoder.withPublicKey(loadPublicKey(key)).build();
        } else {
            // keys are fetched lazily and cached, so this service can start even if the auth service is down
            decoder = NimbusJwtDecoder.withJwkSetUri(sec.jwksUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        }
        if (StringUtils.hasText(sec.issuer())) {
            OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(sec.issuer());
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validator));
        }
        return decoder;
    }

    private static JwtDecoder rejectAllTokens() {
        return token -> {
            throw new BadJwtException("Bearer tokens are not accepted by this instance; call through the api-gateway");
        };
    }

    private static RSAPublicKey loadPublicKey(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            String pem = new String(in.readAllBytes())
                    .replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        }
    }
}
