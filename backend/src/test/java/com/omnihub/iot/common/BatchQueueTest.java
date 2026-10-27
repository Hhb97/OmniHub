package com.omnihub.iot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchQueueTest {

    @Test
    void itemsAreWrittenInBatchesAndNothingIsLostOnClose() {
        List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger total = new AtomicInteger();
        BatchQueue<Integer> q = new BatchQueue<>("t1", 10_000, 500, 20, batch -> {
            sizes.add(batch.size());
            total.addAndGet(batch.size());
            try {
                Thread.sleep(5); // a slow sink: items pile up and the next batch is larger
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < 5000; i++) {
            assertThat(q.offer(i)).isTrue();
        }
        q.close();
        assertThat(total.get()).isEqualTo(5000);
        assertThat(q.written()).isEqualTo(5000);
        assertThat(q.dropped()).isZero();
        assertThat(sizes.size()).as("far fewer writes than items").isLessThan(500);
        assertThat(sizes).allMatch(s -> s <= 500);
    }

    @Test
    void fullQueueDropsAndCountsInsteadOfBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        BatchQueue<Integer> q = new BatchQueue<>("t2", 5, 100, 20, batch -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        q.offer(0); // taken by the worker, which then blocks inside the sink
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        int accepted = 0;
        for (int i = 1; i <= 20; i++) {
            if (q.offer(i)) {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(5);
        assertThat(q.dropped()).isEqualTo(15);
        release.countDown();
        q.close();
    }

    @Test
    void aFailingBatchIsDroppedAndTheWorkerKeepsRunning() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> ok = Collections.synchronizedList(new ArrayList<>());
        BatchQueue<Integer> q = new BatchQueue<>("t3", 100, 1, 20, batch -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("db down");
            }
            ok.addAll(batch);
        });
        q.offer(1);
        await().atMost(Duration.ofSeconds(3)).until(() -> q.dropped() == 1);
        q.offer(2);
        q.offer(3);
        q.close();
        assertThat(ok).containsExactly(2, 3);
    }
}
