package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.config.IotProperties;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Throughput / queue-delay statistics for load tests, logged every 10 seconds while messages are flowing.
 * "delay" = time between the platform receiving the MQTT message and starting to process it. In direct mode that
 * is the in-memory lane wait; in kafka mode it also includes the Kafka round trip.
 */
@Component
public class IngestStats {

    private static final Logger log = LoggerFactory.getLogger(IngestStats.class);
    private static final long WINDOW_MS = 10_000;

    private final String mode;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong sumDelay = new AtomicLong();
    private final AtomicLong maxDelay = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong total = new AtomicLong();

    public IngestStats(IotProperties props) {
        this.mode = props.getIngest().getMode();
    }

    public void record(long delayMs) {
        count.incrementAndGet();
        total.incrementAndGet();
        sumDelay.addAndGet(delayMs);
        maxDelay.accumulateAndGet(delayMs, Math::max);
    }

    /** A message that could not be handed to Kafka and was lost. */
    public void dropped() {
        dropped.incrementAndGet();
    }

    @Scheduled(fixedRate = WINDOW_MS)
    public void report() {
        long n = count.getAndSet(0);
        long d = dropped.getAndSet(0);
        long sum = sumDelay.getAndSet(0);
        long max = maxDelay.getAndSet(0);
        if (n == 0 && d == 0) {
            return;
        }
        log.info("[ingest] mode={} processed={} rate={}/s avgDelay={}ms maxDelay={}ms droppedAtForward={} totalProcessed={}",
                mode, n, n * 1000 / WINDOW_MS, n == 0 ? 0 : sum / n, max, d, total.get());
    }
}
