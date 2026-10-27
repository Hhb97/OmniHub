package com.omnihub.iot.integration.mqtt;

/** Operations on the MQTT broker that the platform needs. Implementation depends on the broker in use. */
public interface BrokerAdmin {

    /** Forcibly disconnects the client (used when a device is disabled, deleted or its secret is rotated). */
    void disconnect(String clientId);
}
