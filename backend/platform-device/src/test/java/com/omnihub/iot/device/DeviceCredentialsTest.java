package com.omnihub.iot.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeviceCredentialsTest {

    @Test
    void secretsAreLongAndUnique() {
        String a = DeviceCredentials.newSecret();
        String b = DeviceCredentials.newSecret();
        assertTrue(a.length() >= 40);
        assertNotEquals(a, b);
    }

    @Test
    void hashVerifies() {
        String salt = DeviceCredentials.newSalt();
        String secret = DeviceCredentials.newSecret();
        String hash = DeviceCredentials.hash(salt, secret);
        assertEquals(64, hash.length());
        assertFalse(hash.contains(secret));
        assertTrue(DeviceCredentials.matches(salt, hash, secret));
        assertFalse(DeviceCredentials.matches(salt, hash, secret + "x"));
        assertFalse(DeviceCredentials.matches(salt, hash, null));
        assertFalse(DeviceCredentials.matches(DeviceCredentials.newSalt(), hash, secret));
    }

    @Test
    void generatedDeviceKeyMatchesPattern() {
        assertTrue(DeviceCredentials.newDeviceKey().matches("^[a-zA-Z0-9_-]{3,64}$"));
    }
}
