package com.uyirhealthapp.payment_service.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * hex(HMAC-SHA256(secret, prefix + "." + body)): the scheme our own callbacks to the modules use.
 * Razorpay signs the body alone: {@link #sign(String, byte[])}.
 */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";

    private Hmac() {
    }

    public static String sign(String secret, String prefix, byte[] body) {
        return hmac(secret, (prefix + ".").getBytes(StandardCharsets.UTF_8), body);
    }

    /** hex(HMAC-SHA256(secret, body)) */
    public static String sign(String secret, byte[] body) {
        return hmac(secret, new byte[0], body);
    }

    private static String hmac(String secret, byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(prefix);
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time comparison of two hex digests, ignoring case. */
    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8),
                actualHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
