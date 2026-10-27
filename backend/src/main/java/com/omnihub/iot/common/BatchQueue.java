package com.omnihub.iot.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded queue drained by one background thread that hands items to a sink in batches ("group commit").
 * While the sink is busy writing, new items accumulate and are written together in the next batch, so the batch
 * size adapts to the load and the database sees a few large statements instead of thousands of tiny ones.
 *
 * - offer() never blocks: when the queue is full the item is dropped and counted (back-pressure must not stall
 *   the MQTT threads).
 * - a sink failure drops that batch (logged), it never kills the worker.
 * - close() writes whatever is still queued before returning.
 */
public final class BatchQueue<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchQueue.class);

    private final String name;
    private final BlockingQueue<T> queue;
    private final int maxBatch;
    private final long idleWaitMs;
    private final Consumer<List<T>> sink;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private volatile boolean running = true;
    private volatile long lastDropLogAt;
    private final Thread worker;

    public BatchQueue(String name, int capacity, int maxBatch, long idleWaitMs, Consumer<List<T>> sink) {
        this.name = name;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxBatch = maxBatch;
        this.idleWaitMs = idleWaitMs;
        this.sink = sink;
        this.worker = new Thread(this::loop, "batch-" + name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** @return false when the queue is full and the item was dropped */
    public boolean offer(T item) {
        if (queue.offer(item)) {
            return true;
        }
        long n = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDropLogAt > 5000) {
            lastDropLogAt = now;
            log.warn("Batch queue '{}' is full: {} items dropped so far (the sink cannot keep up)", name, n);
        }
        return false;
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    public int pending() {
        return queue.size();
    }

    private void loop() {
        List<T> batch = new ArrayList<>(maxBatch);
        while (running || !queue.isEmpty()) {
            try {
                T first = queue.poll(idleWaitMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, maxBatch - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<T> batch) {
        try {
            sink.accept(batch);
            written.addAndGet(batch.size());
        } catch (Exception e) {
            dropped.addAndGet(batch.size());
            log.error("Batch queue '{}' failed to write {} items, they are lost: {}", name, batch.size(), e.toString());
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            worker.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
