package com.omnihub.iot.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveMaskerTest {

    @Test
    void masksPasswordsSecretsAndTokens() {
        String masked = SensitiveMasker.maskJson(
                "{\"username\":\"a\",\"password\":\"hunter2\",\"deviceSecret\":\"abc\",\"accessToken\":\"t0k\",\"x\":1}");
        assertFalse(masked.contains("hunter2"));
        assertFalse(masked.contains("abc"));
        assertFalse(masked.contains("t0k"));
        assertTrue(masked.contains("\"username\":\"a\""));
        assertTrue(masked.contains("\"x\":1"));
    }

    @Test
    void masksBearerTokens() {
        assertFalse(SensitiveMasker.maskJson("Authorization: Bearer eyJhbGciOi.abc.def").contains("eyJhbGciOi"));
    }

    @Test
    void nullSafeAndTruncate() {
        assertEquals(null, SensitiveMasker.maskJson(null));
        assertEquals("abc...", SensitiveMasker.truncate("abcdef", 3));
        assertEquals("abc", SensitiveMasker.truncate("abc", 3));
    }
}
