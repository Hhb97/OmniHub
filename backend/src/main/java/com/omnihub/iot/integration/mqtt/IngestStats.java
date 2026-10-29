package com.omnihub.iot.integration.mqtt;

import com.omnihub.iot.config.IotProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Throughput / queue-delay statistics for load tests and monitoring.
 * - TPS = uplink messages per second that entered the processor (counted per message, all message types).
 * - delay = time between the platform receiving the MQTT message and starting to process it. In direct mode that
 *   is the in-memory lane wait; in kafka mode it also includes the Kafka round trip. It does not include DB time.
 * A 1-second sampler keeps the last 60 seconds for the dashboard page; a 10-second log line is written while busy.
 * The same numbers are published to Prometheus (iot_ingest_*, iot_uplink_*), see docs/operations.md.
 */
@Component
public class IngestStats {

    private static final Logger log = LoggerFactory.getLogger(IngestStats.class);
    private static final long LOG_WINDOW_MS = 10_000;
    private static final int KEEP_SECONDS = 60;

    public record Sample(long ts, long count, long avgDelayMs, long maxDelayMs) {
    }

    public record Snapshot(String mode, double tps, long peakTps, long avgDelayMs, long maxDelayMs, long droppedTotal,
            long processedTotal, List<Sample> series) {
    }

    private final String mode;
    private final MeterRegistry registry;
    private final Counter processedMeter;
    private final Counter droppedMeter;
    private final DistributionSummary delayMeter;
    private final ConcurrentHashMap<String, Counter> byType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> rejected = new ConcurrentHashMap<>();

    // log window (reset every 10s)
    private final AtomicLong logCount = new AtomicLong();
    private final AtomicLong logSumDelay = new AtomicLong();
    private final AtomicLong logMaxDelay = new AtomicLong();
    private final AtomicLong logDropped = new AtomicLong();
    // 1s sampler window
    private final AtomicLong secCount = new AtomicLong();
    private final AtomicLong secSumDelay = new AtomicLong();
    private final AtomicLong secMaxDelay = new AtomicLong();
    // totals
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong droppedTotal = new AtomicLong();

    private final Deque<Sample> samples = new ArrayDeque<>();

    public IngestStats(IotProperties props, MeterRegistry registry) {
        this.mode = props.getIngest().getMode();
        this.registry = registry;
        this.processedMeter = Counter.builder("iot_ingest_processed_total")
                .description("Uplink messages that entered the processor").register(registry);
        this.droppedMeter = Counter.builder("iot_ingest_forward_dropped_total")
                .description("Messages lost while forwarding to Kafka").register(registry);
        this.delayMeter = DistributionSummary.builder("iot_ingest_delay_ms")
                .description("Time from MQTT arrival to start of processing")
                .serviceLevelObjectives(1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000).register(registry);
    }

    public void record(long delayMs) {
        total.incrementAndGet();
        logCount.incrementAndGet();
        logSumDelay.addAndGet(delayMs);
        logMaxDelay.accumulateAndGet(delayMs, Math::max);
        secCount.incrementAndGet();
        secSumDelay.addAndGet(delayMs);
        secMaxDelay.accumulateAndGet(delayMs, Math::max);
        processedMeter.increment();
        delayMeter.record(Math.max(0, delayMs));
    }

    /** Counts a message by kind (PROPERTY / EVENT / STATUS / REPLY). */
    public void type(String type) {
        byType.computeIfAbsent(type, t -> Counter.builder("iot_uplink_messages_total").tag("type", t)
                .description("Uplink messages by kind").register(registry)).increment();
    }

    /** Counts a message that was not processed: unknown_device, disabled, oversize, bad_json, unsupported_topic. */
    public void rejected(String reason) {
        rejected.computeIfAbsent(reason, r -> Counter.builder("iot_uplink_rejected_total").tag("reason", r)
                .description("Uplink messages dropped before processing").register(registry)).increment();
    }

    /** A message that could not be handed to Kafka and was lost. */
    public void dropped() {
        droppedTotal.incrementAndGet();
        logDropped.incrementAndGet();
        droppedMeter.increment();
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        long n = secCount.getAndSet(0);
        long sum = secSumDelay.getAndSet(0);
        long max = secMaxDelay.getAndSet(0);
        synchronized (samples) {
            samples.addLast(new Sample(System.currentTimeMillis(), n, n == 0 ? 0 : sum / n, max));
            while (samples.size() > KEEP_SECONDS) {
                samples.removeFirst();
            }
        }
    }

    /** TPS is averaged over the last 5 complete seconds; avg/max delay over the same span. */
    public Snapshot snapshot() {
        List<Sample> all;
        synchronized (samples) {
            all = new ArrayList<>(samples);
        }
        int from = Math.max(0, all.size() - 5);
        List<Sample> recent = all.subList(from, all.size());
        long cnt = recent.stream().mapToLong(Sample::count).sum();
        long delaySum = recent.stream().mapToLong(s -> s.avgDelayMs() * s.count()).sum();
        double tps = recent.isEmpty() ? 0 : (double) cnt / recent.size();
        long peak = all.stream().mapToLong(Sample::count).max().orElse(0);
        long max = recent.stream().mapToLong(Sample::maxDelayMs).max().orElse(0);
        return new Snapshot(mode, Math.round(tps * 10) / 10.0, peak, cnt == 0 ? 0 : delaySum / cnt, max,
                droppedTotal.get(), total.get(), all);
    }

    @Scheduled(fixedRate = LOG_WINDOW_MS)
    public void report() {
        long n = logCount.getAndSet(0);
        long d = logDropped.getAndSet(0);
        long sum = logSumDelay.getAndSet(0);
        long max = logMaxDelay.getAndSet(0);
        if (n == 0 && d == 0) {
            return;
        }
        log.info("[ingest] mode={} processed={} tps={} avgDelay={}ms maxDelay={}ms droppedAtForward={} totalProcessed={}",
                mode, n, n * 1000 / LOG_WINDOW_MS, n == 0 ? 0 : sum / n, max, d, total.get());
    }
}
