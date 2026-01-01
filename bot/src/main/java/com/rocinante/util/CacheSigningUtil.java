package com.rocinante.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared HMAC signing utility for cache integrity verification.
 *
 * <p>Used by WikiCacheManager and TrainingSpotCache to sign/verify
 * file cache entries, preventing tampering of shared cache data.
 *
 * <p>Requires the signing secret to be set via system property
 * {@code rocinante.wiki.cache.secret} (set by entrypoint.sh from config.json).
 */
@Slf4j
public final class CacheSigningUtil {

    /**
     * System property name for the signing secret.
     */
    private static final String SECRET_PROPERTY = "rocinante.wiki.cache.secret";

    /**
     * HMAC algorithm for signing.
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Cached signing secret (loaded once at class initialization).
     */
    private static final byte[] SIGNING_SECRET;

    static {
        String secretHex = System.getProperty(SECRET_PROPERTY);
        if (secretHex == null || secretHex.isEmpty()) {
            throw new IllegalStateException(
                    "Cache signing secret not configured - set " + SECRET_PROPERTY + " system property");
        }
        SIGNING_SECRET = HexFormat.of().parseHex(secretHex);
        log.info("Cache signing initialized");
    }

    private CacheSigningUtil() {
        // Utility class
    }

    /**
     * Generate HMAC-SHA256 signature for data.
     *
     * @param data the data to sign
     * @return hex-encoded signature
     * @throws IllegalStateException if signing fails
     */
    public static String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(SIGNING_SECRET, HMAC_ALGORITHM));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign cache entry", e);
        }
    }

    /**
     * Verify HMAC-SHA256 signature.
     *
     * @param data      the data that was signed
     * @param signature the signature to verify
     * @return true if valid, false if invalid or missing
     */
    public static boolean verify(String data, String signature) {
        if (signature == null || signature.isEmpty()) {
            log.debug("Cache entry rejected: missing signature");
            return false;
        }

        String expected = sign(data);

        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
        );
    }
}
