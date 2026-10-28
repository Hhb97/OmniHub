package com.omnihub.iot.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * A {@link BatchQueue} split into several shards, each drained by its own thread, so that a write path is not limited
 * to one core / one database connection.
 *
 * An item goes to the shard {@code key % shards}. With the device id as the key all items of one device share a shard:
 * they are written in the order they were offered, and different shards never touch the same device's rows (no row
 * lock conflicts between the writers). The total capacity is divided among the shards, each shard has its own
 * back-pressure (a full shard drops and counts, it never blocks the caller).
 *
 * Metrics are those of BatchQueue, tagged queue=<name> and shard=<index>; sums by queue give the totals.
 */
public final class ShardedBatchQueue<T> implements AutoCloseable {

    private final String name;
    private final BatchQueue<T>[] shards;
    private final ToLongFunction<T> key;

    @SuppressWarnings("unchecked")
    public ShardedBatchQueue(String name, int shardCount, int totalCapacity, int maxBatch, long idleWaitMs,
            ToLongFunction<T> key, Consumer<List<T>> sink) {
        int n = Math.max(1, shardCount);
        this.name = name;
        this.key = key;
        this.shards = new BatchQueue[n];
        int perShard = Math.max(1, totalCapacity / n);
        for (int i = 0; i < n; i++) {
            shards[i] = new BatchQueue<>(name + "-" + i, perShard, maxBatch, idleWaitMs, sink);
        }
    }

    public ShardedBatchQueue<T> bind(MeterRegistry registry) {
        for (int i = 0; i < shards.length; i++) {
            shards[i].bind(registry, name, Tags.of("shard", String.valueOf(i)));
        }
        return this;
    }

    public int shards() {
        return shards.length;
    }

    /** @return false when the shard of this item is full and the item was dropped */
    public boolean offer(T item) {
        return shards[(int) Math.floorMod(key.applyAsLong(item), (long) shards.length)].offer(item);
    }

    public long dropped() {
        long n = 0;
        for (BatchQueue<T> s : shards) {
            n += s.dropped();
        }
        return n;
    }

    public long written() {
        long n = 0;
        for (BatchQueue<T> s : shards) {
            n += s.written();
        }
        return n;
    }

    public int pending() {
        int n = 0;
        for (BatchQueue<T> s : shards) {
            n += s.pending();
        }
        return n;
    }

    @Override
    public void close() {
        for (BatchQueue<T> s : shards) {
            s.close();
        }
    }
}
