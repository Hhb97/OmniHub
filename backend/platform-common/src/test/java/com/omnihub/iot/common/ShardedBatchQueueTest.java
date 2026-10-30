package com.omnihub.iot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShardedBatchQueueTest {

    private record Item(long device, int seq) {
    }

    @Test
    void everyItemIsWrittenAndEachDeviceStaysOnOneWriterInOrder() {
        Map<Long, Set<String>> threadsPerDevice = Collections.synchronizedMap(new HashMap<>());
        Map<Long, List<Integer>> seenPerDevice = Collections.synchronizedMap(new HashMap<>());
        AtomicInteger total = new AtomicInteger();
        ShardedBatchQueue<Item> q = new ShardedBatchQueue<>("s1", 4, 100_000, 200, 10, Item::device, batch -> {
            for (Item i : batch) {
                threadsPerDevice.computeIfAbsent(i.device(), k -> Collections.synchronizedSet(new HashSet<>()))
                        .add(Thread.currentThread().getName());
                seenPerDevice.computeIfAbsent(i.device(), k -> Collections.synchronizedList(new ArrayList<>())).add(i.seq());
            }
            total.addAndGet(batch.size());
        });
        int devices = 50, perDevice = 400;
        for (int seq = 0; seq < perDevice; seq++) {
            for (long d = 0; d < devices; d++) {
                assertThat(q.offer(new Item(d, seq))).isTrue();
            }
        }
        q.close();
        assertThat(total.get()).isEqualTo(devices * perDevice);
        assertThat(q.written()).isEqualTo(devices * perDevice);
        assertThat(q.dropped()).isZero();
        // one device is only ever written by one thread, and in the order it was offered
        assertThat(threadsPerDevice.values()).allMatch(t -> t.size() == 1);
        seenPerDevice.values().forEach(seqs -> assertThat(seqs).isSorted());
        // ... while the devices as a whole use several writers
        Set<String> used = new HashSet<>();
        threadsPerDevice.values().forEach(used::addAll);
        assertThat(used).hasSize(4);
    }

    @Test
    void writersRunInParallel() throws Exception {
        // every sink call waits until all four writers are inside the sink at the same time
        CountDownLatch allIn = new CountDownLatch(4);
        AtomicInteger finished = new AtomicInteger();
        ShardedBatchQueue<Item> q = new ShardedBatchQueue<>("s2", 4, 1000, 10, 10, Item::device, batch -> {
            allIn.countDown();
            try {
                allIn.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.addAndGet(batch.size());
        });
        for (long d = 0; d < 4; d++) {
            q.offer(new Item(d, 0)); // devices 0..3 land on shards 0..3
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> finished.get() == 4);
        assertThat(allIn.getCount()).as("the four writers were in the sink at the same moment").isZero();
        q.close();
    }

    @Test
    void aFullShardDropsAndCountsWithoutBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        // capacity 4 over 2 shards = 2 per shard; the sink blocks, so shard 0 fills up
        ShardedBatchQueue<Item> q = new ShardedBatchQueue<>("s3", 2, 4, 1, 10, Item::device, batch -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        q.offer(new Item(0, 0)); // taken by the blocked writer of shard 0
        started.await();
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (q.offer(new Item(0, i + 1))) { // device 0 -> always shard 0
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(2);
        assertThat(q.dropped()).isEqualTo(8);
        assertThat(q.offer(new Item(1, 0))).as("the other shard is not affected").isTrue();
        release.countDown();
        q.close();
    }

    @Test
    void oneShardBehavesLikeAPlainQueueAndNegativeKeysAreSafe() {
        AtomicInteger total = new AtomicInteger();
        ShardedBatchQueue<Item> q = new ShardedBatchQueue<>("s4", 1, 100, 10, 10, Item::device,
                batch -> total.addAndGet(batch.size()));
        assertThat(q.shards()).isEqualTo(1);
        ShardedBatchQueue<Item> q0 = new ShardedBatchQueue<>("s5", 0, 100, 10, 10, Item::device, batch -> { });
        assertThat(q0.shards()).as("0 or less means one writer").isEqualTo(1);
        for (long d = -3; d < 3; d++) {
            q.offer(new Item(d, 0));
        }
        q.close();
        q0.close();
        assertThat(total.get()).isEqualTo(6);
    }

    @Test
    void metricsCarryTheQueueNameAndOneSeriesPerShard() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ShardedBatchQueue<Item> q = new ShardedBatchQueue<Item>("latest", 3, 3000, 10, 10, Item::device, batch -> { })
                .bind(meters);
        for (int s = 0; s < 3; s++) {
            assertThat(meters.find("iot_batch_queue_pending").tag("queue", "latest").tag("shard", String.valueOf(s))
                    .gauge()).as("shard " + s).isNotNull();
        }
        for (long d = 0; d < 30; d++) {
            q.offer(new Item(d, 0));
        }
        q.close();
        double written = meters.find("iot_batch_queue_written_total").tag("queue", "latest").functionCounters().stream()
                .mapToDouble(c -> c.count()).sum();
        assertThat(written).isEqualTo(30);
    }
}
