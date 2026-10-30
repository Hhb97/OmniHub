package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.messaging.BrokerAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Used with an external broker. Kicking clients would need the broker's management API (e.g. EMQX
 * DELETE /api/v5/clients/{clientid}); that is not implemented, so a disabled / re-keyed device keeps its current
 * connection until it reconnects. The platform still rejects its messages (disabled) and its next
 * authentication fails.
 */
@Component
@ConditionalOnProperty(prefix = "iot.mqtt.embedded-broker", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopBrokerAdmin implements BrokerAdmin {

    private static final Logger log = LoggerFactory.getLogger(NoopBrokerAdmin.class);

    @Override
    public void disconnect(String clientId) {
        log.info("External broker: not disconnecting client {} (kick via broker admin API not implemented)",
                clientId);
    }
}
