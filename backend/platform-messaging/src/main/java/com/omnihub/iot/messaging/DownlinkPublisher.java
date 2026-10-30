package com.omnihub.iot.messaging;

/** Publishes platform -> device messages. */
public interface DownlinkPublisher {

    /** @throws Exception when the message could not be handed to the broker */
    void publish(String topic, byte[] payload, int qos) throws Exception;

    boolean isConnected();
}
