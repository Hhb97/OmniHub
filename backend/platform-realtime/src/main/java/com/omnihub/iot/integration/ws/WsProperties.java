package com.omnihub.iot.integration.ws;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** WebSocket push: serialisation and socket writes never happen on the ingestion threads. */
@Data
@ConfigurationProperties(prefix = "iot.ws")
public class WsProperties {
    private int senderThreads = 2;
    private int queueCapacity = 20000;
    /** property pushes of one device are merged within this window (ms); 0 pushes every message */
    private int coalesceMillis = 200;
}
