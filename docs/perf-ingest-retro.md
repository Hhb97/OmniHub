# 单机 1 万台设备上行的吞吐优化：一次完整的定位与复盘

> 场景：单机 OmniHub（16 核 / 11G 虚拟机），10 000 台设备每台每秒上报 1 条属性消息（每条 7 个属性，约 10k msg/s、70k 属性点/s）。
> 分支：`perf/throughput-bottlenecks`。四个修复，三笔提交。

![上行链路与瓶颈点](img/perf-pipeline.svg)

## 0. 结论

| 指标 | 修复前 | 修复后<br>（关日志 + 16 连接） | 修复后<br>（默认：写日志 + 4 连接） |
|---|---|---|---|
| 后端实际处理 | 约 2.6k msg/s（随检查点抖动） | 9.4k msg/s（跟得上 10k 档位） | **2.0k msg/s** |
| 客户端 ack P95 | 9.8 s | 19 ms | 10.1 s |
| 2 分钟内客户端失败 | 34 204 条，重连 1 500–2 700 次 | 0 条，0 次重连 | 39 990 条，重连 1 639 次 |
| EMQX 会话队列丢弃 | 累计 79.8 万条并持续增长 | 不再增长 | 不再增长（后端在写库处就限流了） |
| 后端批队列丢弃 | history 88.8 万行 | 0 | latest 43.7 万、history 29.3 万 |
| latest 单条写入能力 | 约 2.7 万行/s | 约 10.7 万行/s | 约 1.8 万行/s |

**三列必须一起看。** 只有"关消息日志 + 16 连接"这一组跑到 9.4k/s；出厂默认（写消息日志、4 条连接）仍只有 2.0k/s 且会丢数据——默认配置的瓶颈已经不是本文修的四个点，而是"每条成功上报都往 PostgreSQL 写一行日志"本身，见 §7。

四个瓶颈依次暴露，修掉前一个才会看到后一个——这也解释了为什么"只改一处"的压测结论经常是错的。

## 1. 现象：先分清三种"慢"

压测中最容易混淆的三件事，必须用不同指标区分：

1. **客户端发不出去**：`sent` 上不去，但 `acked ≈ sent`，失败少 → 客户端或网络。
2. **broker 收得下、投不出**：`messages_received` 远大于 `messages_sent`，`delivery_dropped_queue_full` 增长 → 消费侧慢。
3. **后端收得下、写不进**：`iot_ingest_processed_total` 停顿，批队列 `pending` 上升 → 写库慢。

本次是 2 和 3 交替出现，所以只看后端指标会漏掉 broker 侧的证据。

## 2. 定位：七步排除法

![定位路径](img/perf-diagnose.svg)

每一层的具体动作（都可复制）：

```bash
# ① 客户端：sent / acked / failed / ackP95 —— 由压测脚本自报
# ② broker 收与发
curl -s http://localhost:18083/api/v5/prometheus/stats | grep -E '^emqx_(messages_received|messages_sent|delivery_dropped_queue_full) '
# ③ 平台会话的队列与在途
curl -s 'http://localhost:18083/api/v5/clients?username=platform' -H "Authorization: Bearer $T" | grep -o '"mqueue_len":[0-9]*'
# ④ 后端线程栈（空闲也是证据）
docker exec omnihub-backend-1 kill -3 1 && docker compose logs --since 2m backend | awk '/Full thread dump/{f=1} f'
# ⑤ Kafka 分区与消费分配
curl -s localhost:8080/actuator/prometheus | grep kafka_consumer_coordinator_assigned_partitions
# ⑥ PostgreSQL 在等什么（本次的关键一步）
docker compose exec -T postgres psql -U iot -d iot -c \
  "select coalesce(wait_event,'-') w, count(*) from pg_stat_activity where backend_type='client backend' group by 1 order by 2 desc"
# ⑦ 每批写多少行、写多久
curl -s localhost:8080/actuator/prometheus | grep -E '^iot_batch_(size_sum|flush_seconds_sum|flush_seconds_count)'
```

**最重要的一次判断**：第 ④ 步发现 Kafka 消费线程和 MQTT 回调线程**全部空闲**、CPU 只有 0.6 核，而第 ⑥ 步显示 PG 有 13–27 个活跃连接、等待事件反复是 `WALWrite`。
"应用很闲 + 数据库在等磁盘" = 瓶颈不在应用代码，而在每次提交都要等 WAL 落盘这一事实。

在此之前还有一次**同样有价值**的排除：把上行连接从 4 条加到 16 条，吞吐**一点没变**。
如果只看"连接越多越快"的直觉，会在这上面浪费很久；反过来，这个"没有变化"直接证明了不存在"每连接吞吐上限"。

## 3. 四个根因与修复

### 3.1 单连接 = 单会话队列（提交 `5f398d3`）

一条订阅连接就是一个 broker 会话：一个 mqueue（默认 10 万条）+ 一个 Paho 回调线程。
约 6k msg/s 就把队列填满，日志每分钟数万条 `dropped_msg_due_to_mqueue_is_full`，随后 `mailbox_overflow` 直接踢掉平台连接。

修复：`IOT_MQTT_INGEST_CONNECTIONS` 条连接全部订阅同一个 `$share/ingest/...` 共享订阅；EMQX 分发策略 `hash_clientid` 保证同一设备固定落到同一连接（保序），下游 Kafka 仍用 `productKey.deviceKey` 做 key。broker 不支持 `$share` 时自动降级单连接并打 error，避免变成 N 份重复投递。

### 3.2 一条消息写 7 行最新值（提交 `8d2e8bb`）

`iot_device_latest` 原来是 `(device_id, identifier)` 一行，7 个属性就是 7 次 UPDATE。10k×1 需要 7 万行更新/秒，实测 PG 只能到约 2.2 万/秒。

修复：Flyway V3 改成 `device_id` 主键 + `values JSONB`（`{"temp": {"v": 21.5, "t": ...}}`），一条消息 1 行；UPSERT 时逐属性比较 `t`，迟到旧值不会覆盖新值；旧数据自动聚合迁移。

### 3.3 每批太小：批量 = 到达速率 × 写入耗时（提交 `6c74553`）

实测每个 writer 的批次只有 135 行、却要 24ms——单行 0.18ms，几乎全是固定开销（一次 INSERT 往返 + 一次 commit + 一次 fsync）。

更糟的是这是**正反馈**：写入越慢，两批之间到达的数据越少，批次就越小，单行成本就越高。

修复：`BatchQueue` 增加 `lingerMs`（默认 50ms）。第一批到达后继续收集最多 50ms 再写，批次大小变成"50ms × 速率"，与写入快慢解耦。效果：latest 135 → 981 行/批，history 221 → 2 226 点/批。对 InfluxDB 这类"一次 HTTP 请求写一批"的目标收益最大。

### 3.4 每批 commit 都在等 WAL 刷盘（提交 `6c74553`）

这是真正的总闸门。`pg_stat_activity` 的 `wait_event=WALWrite` 说明会话已提交、正在等 WAL fsync；此时 PG 的 CPU 打满 300–500%，应用侧线程却空闲。

修复：只给 ingestion 连接池设置 `SET synchronous_commit = off`（`iot.ingest.synchronous-commit`，白名单校验，默认 `off`）。

- 影响范围只有 ingest 池（批写、设备状态、告警评估），HTTP/API 请求仍走默认的完全同步。
- 代价要说清楚：**操作系统崩溃**时，最后几百毫秒的最新值/历史/消息日志可能丢失；数据库本身不会损坏（不会出现半写页）。历史数据主要落在 InfluxDB，不受影响。
- 想回到旧行为：`IOT_INGEST_SYNCHRONOUS_COMMIT=on`。

## 4. 结果

![修复前后对比](img/perf-results.svg)

修复后同一档位（stage P）：客户端 `sent 849 936 / acked 849 600 / failed 0`，ackAvg 10ms、ackP95 19ms；
后端处理 9.4k msg/s，PG 等待事件从 `WALWrite` 变成普通 `ClientRead`，`delivery_dropped_queue_full` 在整个窗口内零增长，三个批队列 pending 全部回落到 0。

## 5. 可复用的经验

1. **先建"分层计数"，再读代码。** 客户端 → broker → 消费 → 队列 → 数据库，每层都有计数；哪个相邻两层之间出现"递减且下游不积压"，瓶颈就在那个交界处。只盯一个指标（比如后端 CPU）永远会走偏。
2. **线程空闲是强证据，不是"没问题"。** 消费线程 WAITING + 队列不涨 + 下游数据库繁忙 = 阻塞在 I/O。反过来 CPU 打满才是应用侧计算问题。
3. **PG 的 `wait_event` 要当一等指标。** `WALWrite` / `WALSync` = 等磁盘；`DataFileRead` = 缓存不够；`LWLock` 才是锁竞争。只看 `pg_active` 数量会误判成"CPU 不够"。
4. **"批量写入"必须把批次大小做成指标。** 只写吞吐不写 batch size，就看不到 3.3 那个正反馈。经验值：批次小于 200 行时，固定开销通常已经占主导。
5. **并行度要"验证生效"，不要"配置了就相信"。** 16 条连接吞吐不变，这个否定结论比任何肯定结论都省时间。
6. **单变量优先用开关，不要重编镜像。** 改 `.env` + 重启单个容器（40 秒）比重新构建部署（几分钟）快一个数量级，也更不容易引入变量。
7. **注意计数器生命周期。** 后端重启后 `iot_ingest_processed_total` 从 0 开始，而 EMQX 的计数器跨重启保留；比较必须取同一时间窗口的差值，否则会得出荒谬的结论（本文所有速率都是差值计算）。
8. **压测机与被测机同物理机时，宿主机 CPU 会互相抢。** 16 核 VM 上"宿主机 55–80%、VM steal=0"才说明瓶颈在被测系统内部而不是资源争抢。

## 6. 遗留与注意

- 默认配置（写消息日志、4 条连接）的复测结果见 §7：只有 2.0k msg/s 且会丢数据。9.4k/s 只在"关日志 + 16 连接"下成立，不要当成默认配置的承诺值。
- `history` 写入 InfluxDB 的单点能力约 16 万点/s（2 个 writer，每批 2 226 点 / 27.9ms），高于当前 70k 点/s 的需求，但余量不大；再翻倍设备量要加 writer 或换批量协议。
- `synchronous_commit=off` 的取舍按业务定：如果消息日志要当审计证据，建议只对 latest/history 关闭，把消息日志留在独立池上同步提交。
- 压测脚本、开关脚本和采样器在 `scripts/` 与部署机上，属于一次性工具；`docs/tuning.md` §8 保留了逐轮变更记录。

## 7. 默认配置复测：瓶颈换成了消息日志

把 VM 的 `.env` 恢复成出厂默认（`IOT_LOG_OK_PROPERTY=true`、`IOT_MQTT_INGEST_CONNECTIONS=4`）后重启后端，用完全相同的 10k×1、120s 脚本再跑一次（stage Q）：

```
sent=834 540  acked=753 923  failed=39 990
ackAvg=5 220ms  ackP95=10 110ms  reconnects=1 639  connFail=340
后端处理 242 928 条 / 120s ≈ 2.0k msg/s
```

比修复前的 2.6k 还低一点，但**回退的正是默认配置**——说明前四个修复并没有失效，只是被一个新的上限截住了。

### 7.1 证据：每个批量写入线程实际能写多少

| 队列 | 每批行数 | 每次写入耗时 | 单写入能力 | 合计 | 需求 |
|---|---|---|---|---|---|
| message-log | 166.8 | **79.75 ms** | 2.1k 行/s | 4.2k/s（2 线程） | **10k/s** |
| latest | 549.8 | 125.43 ms | 4.4k 行/s | 17.5k/s（4 线程） | 70k/s（已被采样压到～14k） |
| history | 1 105.6 | 58.49 ms | 18.9k 点/s | 37.8k/s（2 线程） | 70k/s |

对比 stage P（同一套代码、仅把日志关掉）：latest 每批 36.55 ms、history 每批 27.90 ms。**同一份 SQL，耗时翻 2–3 倍**，说明三条队列并不是各自独立的，它们在抢同一个磁盘和同一份 buffer cache。

同期 `pg_stat_activity` 的等待事件从 `ClientRead` 变成 `DataFileRead`（300–500 次/采样窗口），即 PostgreSQL 已经在从磁盘读页面了——shared_buffers 被 6 GB 的日志表挤出去了。

### 7.2 为什么这条表天生快不了

```
iot_device_message_log: 16 340 297 行 / 6 192 MB
  pkey                   351 MB
  idx_msglog_device_time 842 MB   （device_id, created_at DESC；随机插入）
  idx_msglog_time        367 MB   （created_at）
```

每插一行 = 1 次堆写 + 3 次索引写（其中 `device_id` 索引是跨设备的随机叶页更新），外加最多 2000 字符的 payload 进 WAL。

按实测 4.2k 行/s 的形态算账，这条表**即使不压测**也是不可持续的：

- 10k msg/s × 每条约 370 字节（行+索引）≈ 3.7 MB/s ≈ **13 GB/小时**，一天 300 GB；
- 本次测试 3 小时多就攒到 6.2 GB / 1634 万行，而 `idx_scan` 只有 934 / 639 次——**写得多、读得极少**。

所以"调参让它跑到 10k"没有意义：即使索引再优化一倍，也只能到 8k，而磁盘增长仍然是个位数小时就爆。

### 7.3 结论与建议（按代价从低到高）

1. **默认关掉"每条成功上报都写日志"**（`IOT_LOG_OK_PROPERTY=false`）。代价：看板的"今日消息数"与趋势会少算——这已经是现有开关的已知行为，`.env.example` 里有说明。仅此一项，stage P 就在 16 连接下跑满 9.4k/s；4 连接下的组合本轮没测（见下表）。
2. **保留日志但按设备采样**：每个设备每 N 秒最多留一条（和 §3.3 的 latest 采样同一个思路），10k 设备 / 30s = 333 行/s，写入量可以忽略，趋势仍然有形状（但是"抽样趋势"）。
3. **改表形态**：`created_at` 索引换 BRIN（追加写 + 范围查询，体积约为 B-tree 的 1/100）、payload 另存、表按天分区或改 `UNLOGGED`。这能把上限从 4.2k 拉到 8k 量级，仍然不够 10k × 1 条/秒。
4. **根本解**：消息明细不进 PostgreSQL——写 Kafka / InfluxDB / 对象存储，PG 只保留"每设备每分钟计数"这类聚合行（10k 设备 = 167 行/s）。

### 7.4 本轮配置矩阵（未测项已标出）

| 消息日志 | 上行连接 | 后端处理 | ackP95 | 客户端失败 | 状态 |
|---|---|---|---|---|---|
| 关 | 16 | 9.4k/s | 19 ms | 0 | 已测（stage P） |
| 开 | 4 | 2.0k/s | 10.1 s | 39 990 | 已测（stage Q，默认配置） |
| 关 | 4 | — | — | — | 未测（复测被中断） |
| 开 | 16 | — | — | — | 未测 |

已知的两个格子不能直接外推：stage P 与 stage Q 同时改了两个变量（日志开关、连接数），"关日志 + 4 连接"这一档是判断"4 条连接是否够用"的关键，但它在本次中断了，**不要用插值代替测量**。

### 7.5 复测本身的局限

- 单次 120s、未做多轮取中位数；`failed` 里混有"客户端主动重连"与"真超时"，只有配合 `connFail`/`lost` 才能分开看。
- 采样器 3–8s 一个点，且指标计数器在后端重启后归零；本节的速率全部用同一窗口的差值计算。
- 压测机与被测 VM 在同一台物理机上（宿主 CPU 峰值 55–80%），本轮没有做"仅压测机空闲"的对照，宿主机争抢对 2.0k 与 9.4k 两组数字的影响方向相同，但不排除绝对值有偏差。
