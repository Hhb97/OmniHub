# 单机 1 万台设备上行的吞吐优化：一次完整的定位与复盘

> 场景：单机 OmniHub（16 核 / 11G 虚拟机），10 000 台设备每台每秒上报 1 条属性消息（每条 7 个属性，约 10k msg/s、70k 属性点/s）。
> 分支：`perf/throughput-bottlenecks`。四个修复，三笔提交。

![上行链路与瓶颈点](img/perf-pipeline.svg)

## 0. 结论

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 后端实际处理 | 约 2.6k msg/s（随检查点抖动） | 9.4k msg/s（跟得上 10k 档位） |
| 客户端 ack P95 | 9.8 s | 19 ms |
| 2 分钟内客户端失败 | 34 204 条，重连 1 500–2 700 次 | 0 条，0 次重连 |
| EMQX 会话队列丢弃 | 累计 79.8 万条并持续增长 | 不再增长 |
| 后端批队列丢弃 | history 88.8 万行 | 0 |
| latest 单条写入能力 | 约 2.7 万行/s | 约 10.7 万行/s |

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

- 修复后的数字是在"关闭消息日志 + 16 条连接"的隔离条件下取得的；恢复默认（写消息日志、4 条连接）后的复测还没做，做之前不要把 9.4k/s 当成默认配置的承诺值。
- `history` 写入 InfluxDB 的单点能力约 16 万点/s（2 个 writer，每批 2 226 点 / 27.9ms），高于当前 70k 点/s 的需求，但余量不大；再翻倍设备量要加 writer 或换批量协议。
- `synchronous_commit=off` 的取舍按业务定：如果消息日志要当审计证据，建议只对 latest/history 关闭，把消息日志留在独立池上同步提交。
- 压测脚本、开关脚本和采样器在 `scripts/` 与部署机上，属于一次性工具；`docs/tuning.md` §8 保留了逐轮变更记录。
