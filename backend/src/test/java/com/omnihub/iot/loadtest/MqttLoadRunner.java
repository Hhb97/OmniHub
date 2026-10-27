package com.omnihub.iot.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * MQTT load generator (not a JUnit test: the name does not match surefire's patterns).
 *
 * Reads the devices from the JSON file exported by the platform's "batch create devices" dialog, connects every one
 * of them to the broker over real MQTT and publishes property reports at a fixed rate per device (default: 1 message
 * per second per device). No database access is needed.
 *
 * Device file: [{"productKey": "...", "deviceKey": "...", "deviceSecret": "..."}, ...]  ("password" is accepted
 * instead of "deviceSecret"; extra fields such as name / brokerUrl / clientId are ignored).
 * Payloads follow the properties of a thing-model JSON (default: src/test/resources/loadtest/thing-model.json).
 *
 * Run it with scripts/loadtest.ps1 (Windows) or scripts/loadtest.sh, or directly from the IDE; see docs/loadtest.md.
 */
public final class MqttLoadRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ configuration

    record Config(String devicesFile, String modelFile, String broker, int qos, int limit, int offset, int shardIndex,
            int shardCount, double rate, int duration, int ramp, boolean midValues, String csv, int reportSeconds) {

        static Config parse(String[] args) {
            Map<String, String> o = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (!args[i].startsWith("--")) {
                    continue;
                }
                String k = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    o.put(k, args[++i]);
                } else {
                    o.put(k, "true");
                }
            }
            String[] shard = o.getOrDefault("shard", "0/1").split("/");
            return new Config(o.getOrDefault("devices", env("LT_DEVICES", "devices.json")), o.get("model"),
                    o.getOrDefault("broker", env("IOT_BROKER_URL", "mqtt://localhost:1883")),
                    Integer.parseInt(o.getOrDefault("qos", "1")), Integer.parseInt(o.getOrDefault("limit", "0")),
                    Integer.parseInt(o.getOrDefault("offset", "0")), Integer.parseInt(shard[0]),
                    Integer.parseInt(shard[1]), Double.parseDouble(o.getOrDefault("rate", "1")),
                    Integer.parseInt(o.getOrDefault("duration", "0")), Integer.parseInt(o.getOrDefault("ramp", "200")),
                    !"random".equals(o.get("value-mode")), o.get("csv"),
                    Integer.parseInt(o.getOrDefault("report", "5")));
        }

        void validate() {
            if (rate <= 0 || (qos != 0 && qos != 1) || shardCount < 1 || shardIndex < 0 || shardIndex >= shardCount
                    || ramp < 1 || reportSeconds < 1) {
                throw new IllegalArgumentException("invalid --rate / --qos / --shard / --ramp / --report");
            }
        }
    }

    record Device(int no, String productKey, String deviceKey, String secret) {
        String clientId() {
            return productKey + "." + deviceKey;
        }
    }

    record Prop(String identifier, String dataType, Double min, Double max, Integer maxLength, List<String> enums) {
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }

    private static void log(String m) {
        System.out.println(Instant.now() + " [load] " + m);
    }

    // ------------------------------------------------------------------ input files

    static List<Device> loadDevices(Config c) throws IOException {
        Path file = Path.of(c.devicesFile());
        if (!Files.exists(file)) {
            throw new IOException("device file not found: " + file.toAbsolutePath()
                    + " (pass --devices <file>, the JSON exported by the batch-create dialog)");
        }
        JsonNode rows = JSON.readTree(Files.readString(file));
        if (!rows.isArray()) {
            throw new IOException("device file must contain a JSON array");
        }
        List<Device> all = new ArrayList<>();
        int n = 0;
        for (JsonNode r : rows) {
            String secret = r.hasNonNull("deviceSecret") ? r.get("deviceSecret").asText() : r.path("password").asText("");
            String pk = r.path("productKey").asText("");
            String dk = r.path("deviceKey").asText("");
            n++;
            if (pk.isEmpty() || dk.isEmpty() || secret.isEmpty()) {
                throw new IOException("device #" + n + " lacks productKey / deviceKey / deviceSecret");
            }
            all.add(new Device(n, pk, dk, secret));
        }
        List<Device> out = new ArrayList<>();
        for (Device d : all) {
            if (d.no() % c.shardCount() == c.shardIndex()) {
                out.add(d);
            }
        }
        int from = Math.min(Math.max(0, c.offset()), out.size());
        int to = c.limit() > 0 ? Math.min(out.size(), from + c.limit()) : out.size();
        return new ArrayList<>(out.subList(from, to));
    }

    static List<Prop> loadModel(Config c) throws IOException {
        JsonNode root;
        if (c.modelFile() != null) {
            root = JSON.readTree(Files.readString(Path.of(c.modelFile())));
        } else {
            try (InputStream in = MqttLoadRunner.class.getResourceAsStream("/loadtest/thing-model.json")) {
                if (in == null) {
                    throw new IOException("default thing model resource /loadtest/thing-model.json is missing");
                }
                root = JSON.readTree(in);
            }
        }
        // accept the whole thing model object or just the properties array
        JsonNode props = root.isArray() ? root : root.path("properties");
        List<Prop> list = new ArrayList<>();
        for (JsonNode p : props) {
            JsonNode s = p.path("spec");
            List<String> enums = new ArrayList<>();
            for (JsonNode e : s.path("enumValues")) {
                enums.add(e.path("value").asText());
            }
            list.add(new Prop(p.path("identifier").asText(), p.path("dataType").asText("FLOAT"),
                    s.hasNonNull("min") ? s.get("min").asDouble() : null,
                    s.hasNonNull("max") ? s.get("max").asDouble() : null,
                    s.hasNonNull("maxLength") ? s.get("maxLength").asInt() : null, enums));
        }
        if (list.isEmpty()) {
            throw new IOException("the thing model has no properties");
        }
        return list;
    }

    // ------------------------------------------------------------------ payloads

    /** mid: values stay around the middle of the range (+-5% of the span) so alarm rules are not tripped. */
    static Object value(Prop p, boolean mid) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (p.dataType()) {
            case "INT", "FLOAT" -> {
                double min = p.min() == null ? 0 : p.min();
                double max = p.max() == null ? 100 : p.max();
                double span = max - min;
                double v = mid ? min + span / 2 + (r.nextDouble() - 0.5) * span * 0.1 : min + r.nextDouble() * span;
                v = Math.min(max, Math.max(min, v));
                return "INT".equals(p.dataType()) ? (Object) Math.round(v) : (Object) (Math.round(v * 100) / 100.0);
            }
            case "BOOL" -> {
                return r.nextBoolean();
            }
            case "STRING" -> {
                return "x".repeat(Math.min(8, p.maxLength() == null ? 8 : p.maxLength()));
            }
            case "ENUM" -> {
                return p.enums().isEmpty() ? "0" : p.enums().get(r.nextInt(p.enums().size()));
            }
            case "JSON" -> {
                return Map.of("v", r.nextInt(100));
            }
            default -> {
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ statistics

    static final class Stats {
        final LongAdder sent = new LongAdder();
        final LongAdder acked = new LongAdder();
        final LongAdder failed = new LongAdder();
        final LongAdder authFail = new LongAdder();
        final LongAdder errors = new LongAdder();
        final LongAdder reconnects = new LongAdder();
        final AtomicInteger connected = new AtomicInteger();
        final AtomicLong latSum = new AtomicLong();
        final AtomicLong latN = new AtomicLong();
        final long[] lats = new long[50_000];
        final AtomicInteger latIdx = new AtomicInteger();
        long totalSent;
        long totalAcked;
        long totalFailed;
        int peakConnected;

        void latency(long ms) {
            latSum.addAndGet(ms);
            latN.incrementAndGet();
            int i = latIdx.getAndIncrement();
            if (i < lats.length) {
                lats[i] = ms;
            }
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Config c = Config.parse(args);
        c.validate();
        List<Device> devices;
        List<Prop> props;
        try {
            devices = loadDevices(c);
            props = loadModel(c);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        if (devices.isEmpty()) {
            System.err.println("no devices to run (check --devices / --limit / --offset / --shard)");
            System.exit(1);
        }
        URI uri = URI.create(c.broker());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 1883;
        double intervalMs = 1000.0 / c.rate();
        log(devices.size() + " devices from " + c.devicesFile() + " -> " + c.broker() + ", " + c.rate()
                + " msg/s per device = " + Math.round(devices.size() * c.rate()) + " msg/s total, qos " + c.qos()
                + ", ramp " + c.ramp() + "/s, properties " + props.stream().map(Prop::identifier).toList());

        Stats st = new Stats();
        AtomicBoolean stopping = new AtomicBoolean();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        List<Mqtt3AsyncClient> clients = new ArrayList<>(devices.size());
        MqttQos qos = c.qos() == 0 ? MqttQos.AT_MOST_ONCE : MqttQos.AT_LEAST_ONCE;
        long startedAt = System.currentTimeMillis();

        // connect in batches so the broker and the platform's auth hook are not hit by everything at once
        AtomicInteger next = new AtomicInteger();
        int perTick = Math.max(1, c.ramp() / 10);
        scheduler.scheduleAtFixedRate(() -> {
            for (int k = 0; k < perTick; k++) {
                int i = next.getAndIncrement();
                if (i >= devices.size()) {
                    return;
                }
                try {
                    Mqtt3AsyncClient cl = startDevice(devices.get(i), props, c, host, port, qos, intervalMs, st,
                            scheduler, stopping);
                    synchronized (clients) {
                        clients.add(cl);
                    }
                } catch (Exception e) {
                    st.errors.increment();
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        Path csv = c.csv() == null ? null : Path.of(c.csv());
        if (csv != null && !Files.exists(csv)) {
            Files.writeString(csv, "time,connected,sent_per_s,acked_per_s,failed,avg_ack_ms,p95_ack_ms,auth_fail,errors,reconnects\n");
        }
        scheduler.scheduleAtFixedRate(() -> report(c, st, devices.size(), csv), c.reportSeconds(), c.reportSeconds(),
                TimeUnit.SECONDS);

        Runnable shutdown = () -> {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            double secs = (System.currentTimeMillis() - startedAt) / 1000.0;
            log(String.format("stopping after %.0fs: sent=%d acked=%d failed=%d avgSent=%.1f/s peakConnected=%d/%d",
                    secs, st.totalSent + st.sent.sum(), st.totalAcked + st.acked.sum(), st.totalFailed + st.failed.sum(),
                    (st.totalSent + st.sent.sum()) / secs, st.peakConnected, devices.size()));
            synchronized (clients) {
                for (Mqtt3AsyncClient cl : clients) {
                    try {
                        cl.disconnect();
                    } catch (Exception ignored) {
                        // already disconnected
                    }
                }
            }
            scheduler.shutdown();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown));
        if (c.duration() > 0) {
            scheduler.schedule(() -> {
                shutdown.run();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }, c.duration(), TimeUnit.SECONDS);
        }
        Thread.currentThread().join();
    }

    private static Mqtt3AsyncClient startDevice(Device dev, List<Prop> props, Config c, String host, int port,
            MqttQos qos, double intervalMs, Stats st, ScheduledExecutorService scheduler, AtomicBoolean stopping) {
        String clientId = dev.clientId();
        String base = "iot/" + dev.productKey() + "/" + dev.deviceKey();
        String topic = base + "/property/post";
        AtomicBoolean up = new AtomicBoolean();
        AtomicBoolean everConnected = new AtomicBoolean();
        AtomicLong seq = new AtomicLong();

        Mqtt3AsyncClient client = MqttClient.builder().useMqttVersion3().identifier(clientId).serverHost(host)
                .serverPort(port).automaticReconnectWithDefaultConfig().addConnectedListener(ctx -> {
                    if (up.compareAndSet(false, true)) {
                        int n = st.connected.incrementAndGet();
                        st.peakConnected = Math.max(st.peakConnected, n);
                        if (everConnected.getAndSet(true)) {
                            st.reconnects.increment();
                        }
                    }
                }).addDisconnectedListener(ctx -> {
                    if (up.compareAndSet(true, false)) {
                        st.connected.decrementAndGet();
                    }
                }).buildAsync();

        client.connectWith().keepAlive(30).cleanSession(true).simpleAuth().username(clientId)
                .password(dev.secret().getBytes(StandardCharsets.UTF_8)).applySimpleAuth().willPublish()
                .topic(base + "/status").payload("{\"state\":\"offline\"}".getBytes(StandardCharsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE).applyWillPublish().send().whenComplete((ack, err) -> {
                    if (err != null) {
                        if (err instanceof Mqtt3ConnAckException) {
                            st.authFail.increment();
                        } else {
                            st.errors.increment();
                        }
                        return;
                    }
                    client.publishWith().topic(base + "/status")
                            .payload("{\"state\":\"online\"}".getBytes(StandardCharsets.UTF_8))
                            .qos(MqttQos.AT_LEAST_ONCE).send();
                    // random phase so that devices do not all publish at the same instant of every second
                    long phase = (long) (ThreadLocalRandom.current().nextDouble() * intervalMs);
                    scheduler.scheduleAtFixedRate(() -> {
                        if (stopping.get() || !up.get()) {
                            return;
                        }
                        Map<String, Object> params = new LinkedHashMap<>();
                        for (Prop p : props) {
                            params.put(p.identifier(), value(p, c.midValues()));
                        }
                        String body;
                        try {
                            body = JSON.writeValueAsString(Map.of("id", dev.no() + "-" + seq.incrementAndGet(), "ts",
                                    System.currentTimeMillis(), "params", params));
                        } catch (IOException e) {
                            st.errors.increment();
                            return;
                        }
                        long t0 = System.nanoTime();
                        st.sent.increment();
                        client.publishWith().topic(topic).payload(body.getBytes(StandardCharsets.UTF_8)).qos(qos).send()
                                .whenComplete((r, e) -> {
                                    if (e != null) {
                                        st.failed.increment();
                                    } else {
                                        st.acked.increment();
                                        st.latency((System.nanoTime() - t0) / 1_000_000);
                                    }
                                });
                    }, phase, Math.max(1, (long) intervalMs), TimeUnit.MILLISECONDS);
                });
        return client;
    }

    private static void report(Config c, Stats st, int total, Path csv) {
        long sent = st.sent.sumThenReset();
        long acked = st.acked.sumThenReset();
        long failed = st.failed.sumThenReset();
        long n = st.latN.getAndSet(0);
        long sum = st.latSum.getAndSet(0);
        int cnt = Math.min(st.latIdx.getAndSet(0), st.lats.length);
        long p95 = 0;
        if (cnt > 0) {
            long[] copy = Arrays.copyOf(st.lats, cnt);
            Arrays.sort(copy);
            p95 = copy[(int) (cnt * 0.95)];
        }
        st.totalSent += sent;
        st.totalAcked += acked;
        st.totalFailed += failed;
        double secs = c.reportSeconds();
        long avg = n == 0 ? 0 : sum / n;
        log(String.format("connected=%d/%d sent=%.0f/s acked=%.0f/s failed=%d ackAvg=%dms ackP95=%dms authFail=%d errors=%d reconnects=%d",
                st.connected.get(), total, sent / secs, acked / secs, failed, avg, p95, st.authFail.sum(),
                st.errors.sum(), st.reconnects.sum()));
        if (csv != null) {
            try {
                Files.writeString(csv, String.format("%s,%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d%n", Instant.now(),
                        st.connected.get(), sent / secs, acked / secs, failed, avg, p95, st.authFail.sum(),
                        st.errors.sum(), st.reconnects.sum()), StandardOpenOption.APPEND);
            } catch (IOException e) {
                log("cannot write csv: " + e.getMessage());
            }
        }
    }
}
