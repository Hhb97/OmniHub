package com.omnihub.iot.integration.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TopicsTest {

    @Test
    void parsesDeviceTopics() {
        var p = Topics.parse("iot/demo-sensor/sim-001/property/post").orElseThrow();
        assertEquals("demo-sensor", p.productKey());
        assertEquals("sim-001", p.deviceKey());
        assertEquals("property/post", p.kind());
        assertEquals("demo-sensor.sim-001", p.clientId());
    }

    @Test
    void rejectsForeignTopics() {
        assertFalse(Topics.parse("other/a/b/c").isPresent());
        assertFalse(Topics.parse("iot/a/b").isPresent());
    }

    @Test
    void prefixAndDownlink() {
        assertEquals("iot/p/d/", Topics.prefix("p.d"));
        assertEquals("iot/p/d/command/down", Topics.commandDown("p", "d"));
        assertTrue(Topics.UPLINK_KINDS.contains("command/reply"));
        assertFalse(Topics.UPLINK_KINDS.contains("command/down"));
    }

    @Test
    void sharedSubscriptionPrefixesTheFilter() {
        assertEquals("$share/ingest/iot/+/+/property/post",
                Topics.shared("ingest", "iot/+/+/property/post"));
        // every platform subscription can be shared, including the multi-level suffix
        for (String f : Topics.PLATFORM_SUBSCRIPTIONS) {
            assertTrue(Topics.shared("ingest", f).startsWith("$share/ingest/"));
        }
    }

    @Test
    void dedupCacheDetectsRepeatsAndEvicts() {
        var c = new UplinkProcessor.DedupCache(2);
        assertFalse(c.seen("a"));
        assertTrue(c.seen("a"));
        assertFalse(c.seen("b"));
        assertFalse(c.seen("c")); // capacity 2: evicts the eldest entry, "a"
        assertTrue(c.seen("b"));
        assertFalse(c.seen("a"));
    }
}
