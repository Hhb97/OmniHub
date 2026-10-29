# 上行链路（ingest）

设备的一条属性上报从 EMQX 到落库，要经过**接入 → 在途窗口 → 进程内队列 → 业务处理 → 批量写**五段。每一段都有自己的上限，而且**积压只会出现在相邻两层之间**——这是判断瓶颈的唯一可靠方法。

本文描述当前实现（默认值见 [configuration.md](configuration.md)）。历史定位过程见 [perf-ingest-retro.md](perf-ingest-retro.md)。

## 1. 链路总览

```
设备 (每台 1 条 MQTT 连接)
   │  iot/{pk}/{dk}/property|event|status|command/reply
   ▼
EMQX ── 共享订阅 $share/ingest/... ──┬── 平台连接 1 ─┐
                                     ├── 平台连接 2 ─┤  每条连接 = 1 个 broker 会话
                                     │      ...      ├─ = 1 个 mqueue + 1 个回调线程
                                     └── 平台连接 16 ┘
                                                │
                              direct │        │ kafka
                                     ▼        ▼
                          lane（单线程，按设备哈希）   Kafka(iot-uplink) → 消费者
                                     └────┬────┘
                                          ▼
                                   UplinkProcessor
                         解析 topic / 校验设备 / 16KB / JSON / 去重
                                          │
                                          ▼
                                    TelemetryService
                          按物模型逐属性校验，然后分成几条写路径：
                            latest 队列 ──► PG iot_device_latest（一台设备一行）
                            history 队列 ─► PG 历史表 或 InfluxDB
                            message-log 队列 ─► PG 通信记录（默认只记失败/事件/指令）
                            DeviceRuntime ──► 内存 + 每 3s 批量刷 last_comm_at
                                          │
                                          ├─► Spring 事件 → WebSocket 推送
                                          └─► 告警评估队列 → AlarmEngine
```

下行指令不走这条链路：后端直接发布到 `iot/{pk}/{dk}/command/down`。

## 2. 入口：多连接共享订阅

### 2.1 为什么不是一条连接

一条平台连接就是一个 broker 会话：**一个投递队列 + 一个回调线程**。EMQX 的会话队列（`max_mqueue_len`）先满，然后开始 `dropped_msg_due_to_mqueue_is_full`；再严重就 `mailbox_overflow` 直接把连接断掉。实测单连接约 **6k msg/s** 触顶，此时后端自己的所有计数器（lane、batch、连接池）都是 0——消息根本没到后端。

### 2.2 现在的形态

`IOT_MQTT_INGEST_CONNECTIONS`（默认 16）条 Paho 连接，全部订阅同一个共享组：

```
$share/ingest/iot/+/+/property/post
$share/ingest/iot/+/+/event/post
$share/ingest/iot/+/+/status
$share/ingest/iot/+/+/command/reply
```

- 组名来自 `IOT_MQTT_SHARED_GROUP`（默认 `ingest`）。
- 组内每条消息只投给**一个**订阅者，所以 16 条连接是**分摊**，不是复制。
- 每个会话有自己的 mqueue，总缓冲随连接数线性增长；每条连接也各有一个回调线程。
- broker 拒绝 `$share`（老版本 / 未开启）时，网关自动降级为**单连接**并打一条 error，不会变成 N 份重复投递。降级后容量退回 6k 量级。

### 2.3 分发策略：`hash_clientid`

EMQX 的 `mqtt.shared_subscription_strategy` 决定组内选哪条连接：

| 策略 | 每设备保序 | 说明 |
|---|---|---|
| `round_robin` | ✗ | 轮流分发，均衡最好；同一设备的两条消息可能落到不同连接 |
| `hash_clientid` | ✓ | 按**发布者 clientid** 哈希；同一设备固定落到同一连接，无状态、零额外内存 |
| `sticky` | ✓ | 维护 publisher→subscriber 映射；订阅者掉线要重新分配，开销更高 |
| `hash_topic` | 仅同一 topic 内 | 本平台每台设备有 4 条上行 topic，属性/事件会分到不同连接 |
| `random` / `local` | ✗ | 不用于生产 |

默认 `hash_clientid`。均衡性不是问题：5 000 台设备哈希进 16 个桶，最坏桶约为均值的 1.2 倍；15k msg/s 下约为 1 170 vs 938 msg/s/连接，而窗口只用到 4 879/65 536。

保序之所以重要，是因为 `AlarmEngine` 的"连续 N 次"计数按到达顺序累加（`consecutive` 在内存里），乱序会让它概率性误判；事件/状态的先后也依赖到达顺序。最新值不受影响（JSONB upsert 逐属性比较上报时间），Kafka 分区内也只是把"到达顺序"固定下来。

**成员变化会重哈希**：后端重启或某条连接重连会改变订阅者集合，部分设备换连接，那个窗口内不保证顺序。16 条连接长期稳定，风险很小。

### 2.4 验证生效

```bash
# 实际生效的策略（EMQX 5.7+ 在 mqtt.*，不是 broker.*）
docker compose exec emqx emqx ctl conf show mqtt | grep shared_subscription_strategy

# 64 条共享订阅（16 连接 × 4 个过滤器）
curl -s "http://localhost:18083/api/v5/subscriptions?limit=1" \
  -H "Authorization: Bearer <token>" | head -c 300

# 平台连接数
docker compose exec backend sh -c \
  'wget -qO- --header="Authorization: Bearer $IOT_METRICS_TOKEN" \
   http://localhost:8080/actuator/prometheus' | grep iot_mqtt_gateway_connections
```

指标：`iot_mqtt_gateway_connections`（已连接）、`iot_mqtt_gateway_connections_expected`（配置值）。

## 3. 在途窗口与容量公式

### 3.1 两边的窗口不是一回事

QoS1 下，broker 投递一条消息后要等 PUBACK 才释放一个窗口位。**上行接收窗口只由 broker 一侧决定**：

- **broker 侧**：`EMQX_MAX_INFLIGHT`（`mqtt.max_inflight`，默认 4096）——每个订阅会话最多有多少条未确认消息。
- **平台侧**：`IOT_MQTT_MAX_INFLIGHT`（Paho `maxInflight`）——限制的是**平台自己发布**的未确认消息数（下行指令），
  **不影响 broker 往平台投递多少条**。MQTT 3.1.1 没有接收窗口协商，Paho 也没有"接收窗口"这个参数。

所以 `IOT_MQTT_MAX_INFLIGHT` 对上行吞吐没有作用；把它设成 4096 只是保证下行指令不会成为瓶颈。真正要调的是
`EMQX_MAX_INFLIGHT`。这一点在复盘里有数据反证：stage T 的 inflight 精确停在 8 192 = 8 × 1024，而当时 Paho 侧是
1000——如果"取小"成立，应该停在 8 000（见 [perf-ingest-retro.md](perf-ingest-retro.md) §8.3 的更正）。

窗口满了以后，消息进入该会话的 mqueue（`EMQX_MAX_MQUEUE_LEN`，默认 10 万）；mqueue 也满了才丢。

### 3.2 公式

```
平台消费能力 ≈ 会话数 × broker 每会话在途窗口 ÷ 单条消息的确认往返
```

确认往返包括网络、broker 调度、后端回调处理。这个公式给出的是**上限**，实际值随往返变长而下降：8 × 1024（8 192 在途位）在 10k 和 12k 的投放量下分别做到 8.0k 和 8.8k msg/s（inflight 7 214/8 192、mqueue 2.8 万，已开始排队）；把投放量加到 15k，确认往返被拖长，同一套配置反而只剩 **4.6k**——inflight 顶死 8 192、mqueue 40 万，而 PG（等待事件只有 `ClientRead`）、三个批队列、Kafka 生产者全部空闲。也就是说 8 会话的真实上限在 9k 附近，超过它不会"慢慢变慢"，而是迅速塌到 4–5k。

### 3.3 实测

| 配置 | 负载 | 结果 |
|---|---|---|
| 1 连接 | — | 约 6k msg/s，mqueue 满 → `mailbox_overflow` 断连 |
| 8 会话 × 1024 | 10k 设备 × 1/s | 8.0k msg/s（16 会话同档 9.4k；客户端发送量、失败数、ackP95 两档相同，8 条已够 10k 档，差别在余量） |
| 8 会话 × 1024 | 12k 设备 × 1.2/s | 8.8k msg/s，inflight 7 214/8 192、mqueue 2.8 万，开始排队 |
| 8 会话 × 1024 | 15k 设备 × 1.5/s | 塌到 **4.6k**：inflight 8 192/8 192、mqueue 40 万 |
| **16 会话 × 4096** | **5 000 设备 × 3/s = 15k** | **1 574 498 条 = EMQX `messages_sent`；mqueue 全程 0；在途峰值 4 879/65 536；broker 零丢弃** |

15k 档的 4 879 峰值说明窗口还有大量余量：真正的限制已经不是窗口，而是下游的 InfluxDB（见 §8）。客户端侧仍有 36 584 次（2.5%）发送失败和 ackP95 3.56 s，但后端一条没少收——那是客户端在长尾下打满自己的节拍。

### 3.4 怎么选

- 上行容量按 `会话数 × EMQX_MAX_INFLIGHT` 估算：`16 × 4096 = 65 536` 在途位；8 × 1024 只有 8 192。
- 小部署：4 会话 × 1024，占内存少，2–4k msg/s 够用。
- **只改 `EMQX_MAX_INFLIGHT` 就能改接收窗口**；`IOT_MQTT_MAX_INFLIGHT` 是下行侧的 Paho 参数。
- **不要只看后端**：窗口问题的特征是"后端所有队列都空、只有 broker 的会话 inflight/mqueue 在涨"。

## 4. 进程内处理：lane

`MqttGateway` 收到消息后不直接处理，而是按**设备**哈希到一条单线程 lane：

- lane 数：`IOT_INGEST_LANES`，`0` = `max(8, 2 × CPU 核数)`。
- 每条 lane 一个 `ArrayBlockingQueue`，长度 `IOT_INGEST_LANE_QUEUE`（默认 20 000）。
- 同一设备永远进同一条 lane，所以**处理顺序 = 到达顺序**；不同设备并行。
- 队列满时：
  - `IOT_LANE_OVERFLOW=block`（默认）：回调线程等待，形成背压，消息不丢；
  - `drop`：丢弃并计入 `iot_ingest_lane_dropped_total`，broker 连接保持快速。
- 指标：`iot_lane_queue_pending`、`iot_lane_queue_max`。

`UplinkProcessor` 在 lane 里做：解析 Topic → 查设备（60 秒内存认证缓存 + 精确失效）→ 启用状态 → 16KB 限制 → JSON 对象校验 → 按消息 `id` 去重（内存，重启失效）→ 交给 `TelemetryService`。非法消息计入 `iot_uplink_rejected_total{reason}`，并（按配置）写通信记录。

## 5. 批量写入：BatchQueue

`latest`、`history`、`message-log` 三个队列都用 `common/BatchQueue.java`：一个有界 `ArrayBlockingQueue` + 一个后台守护线程 + 一个写出回调。

后台线程循环：

1. `poll(100ms)` 取一条；队列空就继续等。
2. 取到后 `drainTo` 把现有消息一次取走，最多 `maxBatch − 1` 条。
3. 整批交给回调写库，回到第 1 步。

**linger（默认 50 ms）**：第一批到达后不立刻写，再攒 50 ms。没有它时批量大小会稳定在"到达速率 × 上次写入耗时"——库越慢批越小，每行摊的固定开销越大。50 ms 让批大约放大 10 倍。

**为什么批会自适应**：写库期间新消息继续进队列，下一轮 `drainTo` 会把这段时间累积的全部取走。负载越高每批越大，每秒语句数不随消息数线性增长。

**入队 `offer` 永不阻塞**：队列满时丢弃并计数，最多每 5 秒打一条
`Batch queue 'x' is full: N items dropped so far`。这样数据库再慢也不会拖住 MQTT 线程。

**写失败**：PG 失败整批丢弃并记 ERROR；Influx 失败等 300 ms 重试一次，仍失败则丢弃。正常关闭会尽量写完，进程崩溃则队列里的数据丢失。

## 6. 五条写入路径

### 6.1 最新值（`iot_device_latest`）

- 一台设备**一行**，`values JSONB`：`{"temperature":{"v":21.5,"t":1793179493731}, ...}`。
- 一条 7 属性的消息只产生 **1 行 UPDATE**（旧设计是 7 行）。
- 同一设备同一属性最多每 `IOT_LATEST_MIN_INTERVAL_MS`（默认 1000 ms）写一次；尾部补偿保证最后一条一定落库。所以页面上的"当前值"最多滞后约 1 秒。
- upsert 时**逐属性比较 `t`**，迟到的旧值不会覆盖新值。
- 它是"当前快照"，不是审计数据；历史库仍然每个样本都写。

### 6.2 历史 — PostgreSQL

- `iot_device_property_history`，每个样本一行，**不去重**。
- 每 5 000 行一块，一条 `INSERT … SELECT … FROM unnest(5 个数组)`。
- `num_value` 是数值投影（布尔 0/1，非数值为空），供趋势聚合。
- `IOT_HISTORY_STORE=postgres` 时使用。

### 6.3 历史 — InfluxDB 1.8

- measurement `device_property`；tag `device_id`、`identifier`；field `num`（浮点，仅数值/布尔）与 `json`（原值 JSON 文本）。
- 每个样本拼一行行协议，毫秒精度：
  ```
  device_property,device_id=1,identifier=temperature num=23.5,json="23.5" 1700000000000
  ```
- 标识符不符合 `^[a-zA-Z][a-zA-Z0-9_]{0,63}$` 的样本直接跳过，防止构造恶意行协议。
- 每 5 000 点 `POST /write?db=<库>&precision=ms[&rp=<保留策略>]` 一次；有用户名则带 Basic 认证；204 视为成功。
- 过期清理由 Influx 保留策略负责；后端的每日清理对 Influx 不做事。
- `IOT_HISTORY_STORE=influxdb` 时使用（compose 默认）。

### 6.4 通信记录（`iot_device_message_log`）

- 每条上行/下行消息入队；载荷先截断到 4 000 字符，**脱敏和再截断在后台线程做**。
- 每批一条 `INSERT … SELECT … FROM unnest(8 个数组)`，字段 topic/message 255、payload 2 000。
- `created_at` 取**入队时刻**，不是写库时刻。
- `IOT_LOG_OK_PROPERTY=false`（默认）：不记录"成功的属性上报"，其余（失败、事件、状态、指令、下行）仍记录。
- **代价**：这张表每行 1 次堆写 + 3 次索引写 + payload，实测上限约 **4.2k 行/s**，10k msg/s 时约 **13 GB/小时**。打开它会把默认档位从 9.4k 拉回 2.0k 并开始丢消息。
- 副作用：仪表盘的"上报趋势 / 今日消息数 / 活跃设备"统计这些记录，关闭后三项会偏低。

### 6.5 设备最后通信时间与在线状态（`DeviceRuntime`）

不用 `BatchQueue`，用"内存记录 + 定时批量刷新"：

- 每条消息 `touch(ref)`：`commAt[deviceId] = now`；如果设备已在 `onlineKnown` 里，**直接返回，不访问数据库**（绝大多数消息）。
- 否则同步执行一条 `UPDATE … WHERE online_status <> 'ONLINE' RETURNING id`，置 ONLINE、加入 `onlineKnown`，状态真的变化时发布 `DeviceStatusEvent`。
- `flushLastComm` 每 3 秒（也在每次离线扫描前）用一条 `UPDATE … FROM unnest(ids, times)` 批量写 `last_comm_at`，每 5 000 台一块；写成功的条目才移除，失败下次重试。
- `sweepOffline` 每 15 秒把"在线但 `last_comm_at` 超过 `device.offline.timeout.seconds`（默认 90 秒）"的设备置 OFFLINE。
- 启动预加载认证缓存时，库里已是 ONLINE 的设备直接进入 `onlineKnown`，避免重启后每台都多做一次 UPDATE。

## 7. Kafka 模式（`IOT_INGEST_MODE=kafka`）

| 模式 | 链路 |
|---|---|
| `direct`（默认） | MQTT 回调 → lane → `UplinkProcessor` |
| `kafka` | MQTT 回调 → **转发 Kafka**（key = `productKey.deviceKey`）→ 消费者 → 同一个 `UplinkProcessor` |

两种模式后面的业务处理完全相同；下行指令都直接发 EMQX，不经过 Kafka。**15k 档的实测就是在 `kafka` 模式下完成的**（单节点 KRaft，8 分区、8 消费者），设备 → EMQX → 转发 Kafka → 消费 → 处理器整条链路已验证。

- 消息格式：JSON 文本 `{"t":"<MQTT topic>","p":"<原始 payload>","r":<收到时间 ms>}`，只支持文本 payload。
- 顺序：同一设备 key 相同 → 同一分区 → 分区内按写入顺序消费。direct 下同一设备也固定在同一条 lane。
- 可靠性：direct 下后端宕机丢内存队列；kafka 下已写入 Kafka 的消息重启后继续消费（`auto-offset-reset=latest`，新消费组不补历史消息）。"MQTT 收到 → 写入 Kafka"失败计入 `iot_ingest_forward_dropped_total`。
- 去重：消息 `id` 的内存去重在两种模式下都有，重启后失效。
- 多实例：**未实现、未验证**。当前共享订阅会让多个后端实例分摊消息，但 Kafka 消费组、告警状态、去重缓存都还是单实例假设。
- 切换：改 `IOT_INGEST_MODE` 后 `docker compose up -d backend`；切回同样。

## 8. 容量与已知上限

单机 16 核 / 11 GB 的实测（详见 [README.md](README.md) 的基准表）：

| 环节 | 实测上限 | 15k × 7 属性的需求 | 结论 |
|---|---|---|---|
| EMQX → 平台（16 × 4096） | > 15k msg/s | 15k msg/s | 有余量（在途峰值 4 879/65 536） |
| lane / UplinkProcessor | 未单独压到顶 | 15k msg/s | 15k 档未成为瓶颈 |
| latest（4 writer） | 约 62k 行/s | 70k 行/s | 靠 1 秒采样撑着，余量很小 |
| history PG / Influx（2 writer） | 约 89k 点/s | 105k 点/s | **会丢点**；改 `IOT_BATCH_HISTORY_WORKERS=4`（理论上限约 180k/s） |
| message-log（2 writer，默认关） | 约 4.2k 行/s | 15k 行/s | 跑不动；保持关闭 |

15k 档的实际结果：平台侧全通（broker 零丢弃、后端全量接收），**唯一在丢的是 InfluxDB 历史，丢了 153 万点**。这不是接入链路的问题，是下游写入能力的问题。

PG 写入还受 `synchronous_commit` 影响：ingest 池默认 `off`（不等 WAL 刷盘），否则批量 writer 会卡在 `pg_stat_activity.wait_event=WALWrite`，整个管道被拖住。代价是操作系统崩溃时丢最近几百毫秒的遥测写入，数据库本身一致。

## 9. 调优顺序

按这个顺序做，每一步都能用指标验证：

1. **先关通信记录**（`IOT_LOG_OK_PROPERTY=false`）。它是最大的写放大，且默认已关。
2. **确认窗口**：接收窗口 = `IOT_MQTT_INGEST_CONNECTIONS × EMQX_MAX_INFLIGHT`；`IOT_MQTT_MAX_INFLIGHT` 只管下行。
3. **确认批量**：`IOT_BATCH_LINGER_MS=50`；看 `iot_batch_size` 是否接近上限、`iot_batch_flush_seconds` 是否变长。
4. **确认 WAL**：`IOT_INGEST_SYNCHRONOUS_COMMIT=off`；PG 的 `wait_event` 不应长期是 `WALWrite`。
5. **再加下游**：latest / history 的 writer 数、InfluxDB 的磁盘与 CPU。
6. **最后才加连接/窗口**：窗口放大只是把压力推给下游，不会提高下游的写入能力。

判断依据永远是"哪一层在积压"，不是"哪一层 CPU 高"。完整方法见 [perf-ingest-retro.md](perf-ingest-retro.md)。

## 10. 观测与代码位置

日志关键字：

- `Batch queue '…' is full` — 丢数据，下游持续跟不上
- `Batch queue '…' failed to write` — 整批写失败
- `Writing latest values failed` / `Flushing device last-communication times failed`
- `MQTT gateway connection N/M lost` — 连接断开（重启 broker 时会一次出现很多条）

指标（节选，完整列表见 [operations.md](operations.md) §1）：

| 指标 | 含义 |
|---|---|
| `iot_ingest_processed_total` | 进入处理器的消息总数，`rate()` 即吞吐 |
| `iot_lane_queue_pending` / `_max` | lane 积压 |
| `iot_batch_queue_pending{queue}` / `_dropped_total` | 批队列积压 / 丢弃 |
| `iot_batch_size{queue}`、`iot_batch_flush_seconds{queue}` | 每批条数、单批耗时 |
| `iot_mqtt_gateway_connections` / `_expected` | 上行连接数 |
| `iot_ingest_forward_dropped_total` | Kafka 模式转发失败 |
| `iot_alarm_eval_dropped_total`、`iot_ws_push_dropped_total` | 告警评估 / WebSocket 推送丢弃 |

| 内容 | 文件 |
|---|---|
| 多连接、共享订阅、lane | `integration/mqtt/MqttGateway.java` |
| 共享订阅组名、过滤器 | `integration/mqtt/Topics.java` |
| 解析、去重、设备校验 | `integration/mqtt/UplinkProcessor.java` |
| 最新值 / 历史 / 重试 | `telemetry/TelemetryService.java` |
| PG 历史批量写 | `telemetry/PostgresHistoryStore.java` |
| Influx 批量写 | `telemetry/InfluxHistoryStore.java` |
| 通信记录 | `telemetry/MessageLogger.java` |
| 最后通信时间 / 在线状态 | `device/DeviceRuntime.java` |
| 批处理队列 | `common/BatchQueue.java` |
| 配置 | `config/IotProperties.java`、`application.yml` |
