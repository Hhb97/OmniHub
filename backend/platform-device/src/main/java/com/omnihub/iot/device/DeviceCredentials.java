package com.omnihub.iot.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Device credential generation and verification. Secrets are 256-bit random values (not user chosen), so a
 * salted SHA-256 is sufficient and keeps MQTT connect authentication cheap. Only the hash and salt are stored.
 */
public final class DeviceCredentials {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceCredentials() {
    }

    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String newDeviceKey() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return "dev" + HexFormat.of().formatHex(b);
    }

    public static String hash(String salt, String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String salt, String expectedHash, String secret) {
        if (secret == null || salt == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(salt, secret).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
