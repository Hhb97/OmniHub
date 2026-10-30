package com.omnihub.iot.messaging;

import java.util.Optional;

/**
 * Topic convention (all payloads are JSON, QoS 1):
 * <pre>
 *   iot/{productKey}/{deviceKey}/property/post   device -> platform
 *   iot/{productKey}/{deviceKey}/event/post      device -> platform
 *   iot/{productKey}/{deviceKey}/status          device -> platform (online / offline, last-will)
 *   iot/{productKey}/{deviceKey}/command/reply   device -> platform
 *   iot/{productKey}/{deviceKey}/command/down    platform -> device
 * </pre>
 */
public final class Topics {

    public static final String ROOT = "iot";
    /** Topic suffixes a device is allowed to publish to. */
    public static final java.util.Set<String> UPLINK_KINDS = java.util.Set.of("property/post", "event/post", "status",
            "command/reply");
    public static final String[] PLATFORM_SUBSCRIPTIONS = { "iot/+/+/property/post", "iot/+/+/event/post",
            "iot/+/+/status", "iot/+/+/command/reply" };

    /** A parsed device topic. {@code kind} is e.g. "property/post". */
    public record Parsed(String productKey, String deviceKey, String kind) {
        public String clientId() {
            return productKey + "." + deviceKey;
        }
    }

    private Topics() {
    }

    public static Optional<Parsed> parse(String topic) {
        String[] p = topic.split("/");
        if (p.length < 4 || !ROOT.equals(p[0])) {
            return Optional.empty();
        }
        String kind = String.join("/", java.util.Arrays.copyOfRange(p, 3, p.length));
        return Optional.of(new Parsed(p[1], p[2], kind));
    }

    public static String commandDown(String productKey, String deviceKey) {
        return ROOT + "/" + productKey + "/" + deviceKey + "/command/down";
    }

    /**
     * A shared subscription makes the broker deliver every message to exactly one of the subscribers in the group,
     * instead of a copy to every one of them.
     */
    public static String shared(String group, String filter) {
        return "$share/" + group + "/" + filter;
    }

    public static String prefix(String clientId) {
        int dot = clientId.indexOf('.');
        return ROOT + "/" + clientId.substring(0, dot) + "/" + clientId.substring(dot + 1) + "/";
    }
}
