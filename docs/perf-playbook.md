# TPS 优化手册：怎么定位瓶颈，怎么改

> 来源：OmniHub 单机（16 vCPU / 11 GB 的 VM，EMQX + 后端 + PostgreSQL + InfluxDB + Kafka 同机）从 1 万条/s 压到 3 万条/s 的全过程。
> 每一节都按同一个顺序写：**现象 → 怎么定位 → 根因 → 怎么改 → 效果 → 注意**。
> 1 万台那一轮的逐步复盘见 [perf-ingest-retro.md](perf-ingest-retro.md)（历史记录）；当前配置和容量公式见 [ingest.md](ingest.md)、[configuration.md](configuration.md)。

## 0. 一页纸结论

**容量**（同一台 VM，5000 台设备 × N 条/s，`kafka` 模式，每条消息 7 个属性）：

| 目标 | 后端实际处理 | history 写入 | 整机 CPU | 结论 |
|---|---|---|---|---|
| 1 万/s | 1.0 万 | 7.0 万点/s | 约 65% | 稳定，0 失败 |
| 1.5 万/s | 1.5 万 | 10.5 万点/s | 约 75% | 稳定，0 丢点 |
| 2.5 万/s（内核调参前） | 1.74 万 | 12.1 万点/s，丢 4 万 | 约 95% | 崩：8395 次重连，ack P95 约 9 秒 |
| 2.5 万/s（内核调参后） | 2.39 万 | 16.8 万点/s，0 丢点 | 约 84% | 基本能扛，ack 延迟缓慢增长 |
| 3.0 万/s | 2.79 万 | 19.5 万点/s，0 丢点 | 约 87% | 超限，延迟线性增长到约 40 秒 |

**这台机器的稳定上限约 2.8 万条/s。** 生产场景（1 万台、每 5 分钟一次 ≈ 33 条/s）只占它的约 1/850，不是容量问题。

**瓶颈是一个接一个暴露的**，修掉一个才看得到下一个，所以"只改一处就压测"的结论经常是错的。按出现顺序：

| # | 瓶颈 | 主要证据 | 改动 |
|---|---|---|---|
| B1 | 单连接 = 单会话队列 | EMQX `dropped_msg_due_to_mqueue_is_full` | 16 条连接共享订阅 |
| B2 | broker → 后端在途窗口 | 会话在途顶死，下游全闲 | `EMQX_MAX_INFLIGHT=4096` |
| B3 | 一条消息写 7 行最新值 | PG 只能 2.2 万行/s | 一设备一行 JSONB |
| B4 | 批太小（正反馈） | 每批 135 行、24 ms | `lingerMs` 凑批 |
| B5 | 每批提交都等 WAL 刷盘 | `wait_event=WALWrite`，应用线程空闲 | ingest 池 `synchronous_commit=off` |
| B6 | 每条成功上报写一行日志 | 默认配置只有 2.0k/s | 默认不记成功上报 |
| B7 | InfluxDB 历史写入跟不上 | 2 个 writer：89k 点/s，丢 153 万点 | writer 4 个 + 分段埋点 |
| B8 | 内核软中断预算耗尽 | `time_squeeze` 每 75 秒 9 万次，软中断 7.6 核 | `netdev_budget` 等 |
| B9 | Kafka 模式的额外开销 | Kafka 容器 1.1～1.3 核 | 待验证 direct 模式 |
| B10 | 整机 CPU 被多个组件分食 | EMQX 调度队列平均 14 | 拆机部署（未做） |

## 1. 方法：先分层计数，再读代码

### 1.1 先分清三种"慢"

1. **客户端发不出去**：`sent` 上不去，但 `acked ≈ sent`，失败少 → 压测机或网络。
2. **broker 收得下、投不出**：`emqx_messages_received` 远大于 `emqx_messages_sent`，`delivery_dropped_queue_full` 增长 → 消费侧慢。
3. **后端收得下、写不进**：`iot_ingest_processed_total` 停顿，批队列 `pending` 上升 → 写库慢。

### 1.2 每一层都要有一个计数

哪两层之间出现"递减，且下游不积压"，瓶颈就在那个交界处。

| 层 | 指标 | 来源 |
|---|---|---|
| 压测端 | `sent / acked / failed / ackP95 / reconnects` | 压测脚本自己每秒输出 |
| broker | `emqx_messages_received / sent / dropped`、`emqx_connections_count`、`emqx_vm_run_queue` | EMQX 的 Prometheus 端点 |
| 后端入口 | `iot_mqtt_gateway_connections`、`iot_ingest_processed_total`、`iot_ingest_lane_dropped_total` | 后端 |
| 批队列 | `iot_batch_queue_pending / written_total / dropped_total`、`iot_batch_size`、`iot_batch_flush_seconds`（均带 `queue` 标签） | 后端 |
| Influx 写入 | `iot_influx_write_seconds{phase="build|http"}`、`iot_influx_write_requests_total{result}`、`iot_influx_write_points_total`、`iot_history_flush_retries_total` | 后端 |
| 数据库 | `pg_stat_activity.wait_event`、`pg_stat_database_xact_commit` | PostgreSQL |
| 整机 | `node_cpu_seconds_total` 按 `mode` 拆（user/system/softirq/iowait）、`node_load1` | node-exporter |
| 容器 | `docker stats --no-stream` | 没装 cAdvisor 时的替代 |
| 内核 | `/proc/softirqs`、`/proc/net/softnet_stat`、`sysctl net.core.*` | 主机 |

**没有这些计数，别动代码。** B7 的 98 ms 就是因为一开始只有整批 flush 的总耗时，分不出是拼请求体、HTTP 还是排队，才补了分段埋点。

### 1.3 五条判断规则

1. **线程空闲是证据，不是"没问题"。** 消费线程全部 WAITING、队列不涨、数据库繁忙 = 阻塞在 I/O，不是应用在算。CPU 打满才是计算问题。
2. **看 `wait_event`，不要只看活跃连接数。** `WALWrite/WALSync` 等磁盘，`DataFileRead` 缓存不够，`LWLock` 才是锁竞争。
3. **整机 CPU 要按 mode 拆。** 总利用率 95% 看不出问题；`softirq` 占 47% 才指向网络路径。
4. **"没有变化"的实验同样有价值。** 4 条连接加到 16 条吞吐不变，直接证明不存在"每连接吞吐上限"，省掉一大堆方向。
5. **一次只改一个变量。** 改 `.env` 并重启单个容器（约 40 秒）比重新构建部署快一个数量级，也不会带进别的变量。

## 2. 瓶颈清单

### B1 单连接 = 单会话队列

- **现象：** 约 6k msg/s 时 EMQX 日志每分钟数万条 `dropped_msg_due_to_mqueue_is_full`，随后 `mailbox_overflow` 把平台连接踢掉。
- **定位：** `emqx_messages_received` 远大于 `emqx_messages_sent`；`/api/v5/clients?username=platform` 里看 `mqueue_len`。
- **根因：** 一条订阅连接 = 一个 broker 会话 = 一个投递队列 + 一个 Paho 回调线程。
- **改：** `IOT_MQTT_INGEST_CONNECTIONS` 条连接加入同一个共享订阅 `$share/ingest/...`，分发策略用 `hash_clientid`，同一设备固定落到同一连接，保序。
- **注意：**
  - EMQX 5.7+ 的配置路径是 `mqtt.shared_subscription_strategy`（环境变量 `EMQX_MQTT__SHARED_SUBSCRIPTION_STRATEGY`）。写成旧的 `EMQX_BROKER__...` 会被**静默忽略**，实际一直是 `round_robin`。改完要用管理接口回读验证。
  - broker 不支持 `$share` 时要降级成单连接，否则变成 N 份重复投递。

### B2 broker → 后端的在途窗口

- **现象：** 8 条连接 × 1024 窗口在 4.6k msg/s 就停住：每个会话都满了，后端线程和数据库都闲。
- **定位：** 会话的 `inflight_cnt` 贴着上限，`mqueue_len` 增长，下游全空闲。
- **根因：** 上行速率上限 ≈ `会话数 × broker 的 max_inflight ÷ 确认往返时间`。**由 broker 的 `max_inflight` 决定，不是客户端的。**
- **改：** `EMQX_MAX_INFLIGHT=4096` + 16 会话，实测维持 15k msg/s 时 broker 队列为空。
- **注意：** 客户端 Paho 的 `setMaxInflight` 只限制**自己往外发**的未确认消息（下行指令），对上行无效。`IOT_MQTT_MAX_INFLIGHT` 因此不影响上行吞吐，这一点曾在文档里写错过。

### B3 一条消息写 7 行最新值

- **现象：** 1 万条/s × 7 属性 = 7 万次 UPDATE/s，PG 只能到约 2.2 万。
- **定位：** 批队列 `latest` 的写入速率远低于到达速率，`pending` 持续上升。
- **根因：** 表设计是 `(device_id, identifier)` 一行，一条消息 = 7 个行版本。
- **改：** 迁移 V3：`device_id` 主键 + `values JSONB`，一设备一行，UPSERT 时逐属性比较时间戳，迟到的旧值不覆盖新值。再加**采样**：同一属性最多每 `latest-min-interval-ms`（默认 1000）写一次，被跳过的暂存，由尾部刷新保证最终写入最新值。
- **效果：** latest 单线程写入能力从约 2.7 万行/s 提到约 10.7 万行/s。

### B4 批太小，而且是正反馈

- **现象：** 每批只有 135 行、却要 24 ms，单行 0.18 ms，几乎全是固定开销（一次往返 + 一次提交 + 一次 fsync）。
- **定位：** 看 `iot_batch_size`（平均批大小）和 `iot_batch_flush_seconds`，**吞吐和批大小必须一起看**。批小于 200 行时固定开销通常已经占主导。
- **根因：** 批大小 = 到达速率 × 上一次写入耗时。写得越慢，批越小，单行成本越高，更慢。
- **改：** `BatchQueue` 加 `lingerMs`（默认 50 ms）：第一条到达后再收集最多 50 ms，批大小变成"50 ms × 速率"，与写入快慢解耦。latest 135 → 981 行/批，history 221 → 2226 点/批。

### B5 每批提交都在等 WAL 刷盘

- **现象：** PG CPU 300～500%，但应用线程全空闲。
- **定位：** `select wait_event, count(*) from pg_stat_activity ... group by 1`，大量 `WALWrite`；同时线程栈显示 Kafka 消费线程和 MQTT 回调线程全 WAITING。这是整轮最关键的一次判断："应用很闲 + 数据库在等磁盘"。
- **改：** 只给 ingest 连接池设 `synchronous_commit=off`（`IOT_INGEST_SYNCHRONOUS_COMMIT`，白名单校验，默认 off）。HTTP/API 请求仍走同步提交。
- **代价必须说清：** 操作系统崩溃时，最后几百毫秒的最新值/历史/日志可能丢；数据库本身不会损坏。想回到旧行为设 `on`。

### B6 每条成功上报写一行日志

- **现象：** 默认配置（写成功上报的日志）只有 2.0k msg/s 且丢数据，而关掉日志后 9.4k msg/s。
- **定位：** 批队列 `message-log` 写入速率远低于其他队列，这张表天然写入重（每行有完整载荷）。
- **改：** `IOT_LOG_OK_PROPERTY` 默认 `false`，只记失败、事件、状态、指令。
- **注意：** 打开后看板的趋势会少算；日志表在 10k msg/s 下每小时约增长 13 GB。

### B7 InfluxDB 历史写入跟不上

- **现象：** 15k 压测里 2 个 writer 约 89k 点/s，需要 105k，丢 153 万点；flush 平均 98 ms。
- **定位（这一步做对了的部分）：**
  1. 先在空闲的 VM 上用同样形状的批次（628 台 × 7 属性 = 4396 点/请求）**单独测 Influx**：1 个 writer 约 10 ms/请求（26 万值/s），4 个 writer 约 78 万值/s，客户端拼请求体只要约 1.3 ms。
  2. 再把 CPU 占到只剩 2 核：同一请求升到 17～22 ms。
  3. 对比压测里的 98 ms：差距在 Influx 之外，需要分段埋点。
  4. 补 `iot_influx_write_seconds{phase}`、请求结果计数、重试计数后重测。
- **根因：** 不是 Influx 容量，也不是 Java 代码慢，是 writer 数量不足 + 整机 CPU 争抢。
- **改：** `IOT_BATCH_HISTORY_WORKERS` 默认从 2 提到 4。**4 个 writer 后 15k 压测写入 10.5 万点/s，0 丢点，flush 平均 30 ms、P95 94 ms。**
- **被证伪的假设：** 给 Influx 加 `wal-fsync-delay=100ms` 以为能提速，实测每个请求多约 95 ms（2 个 writer 下），反而更慢。
- **备选（已测未做）：**
  | 格式 | 请求耗时（空闲，1 writer） | 说明 |
  |---|---|---|
  | 现状：每属性一点，字段 `num` + `json` | 10 ms | 读取路径依赖 `json` |
  | N：数值点不写冗余 `json` | 8 ms（约快 20%） | 读取要回退到 `num`，改动小 |
  | W：每条消息一点，7 属性为字段 | 4 ms（约快 2×），请求体约 1/4 | 要改 measurement 和历史查询，旧数据需迁移或双读 |
- **注意：** 这是写入量问题，减少写入量（每属性最小写入间隔、死区）比任何格式优化都更有效，但要业务同意。

### B8 内核软中断预算耗尽（本轮最意外的一个）

- **现象：** 2.5 万条/s 时后端只处理 1.74 万/s，压测端 8395 次连接被重置，ack P95 约 9 秒，整机 CPU 95%。
- **定位：**
  1. 整机 CPU 按 mode 拆：`user 6.2 / system 1.4 / softirq 7.6 / idle 0.8` 核。**软中断占 47%**，而 10k、15k 时分别只有 1.4、3.5 核，增速远超负载。
  2. 容器 CPU（`docker stats`）：EMQX 5.5、后端 3.1、Influx 1.6、PG 0.9、Kafka 0.7，合计 11.8 核，另有约 3.4 核在内核里，不记到任何容器。
  3. `/proc/net/softnet_stat`：`time_squeeze` 每 75 秒 91,807 次。**这一列表示软中断预算用完被迫退出，是定位点。**
  4. `sysctl net.core.netdev_budget`：默认 300 个包 / `netdev_budget_usecs` 2000 微秒，在每秒约 9 万包的负载下一直被用完。
- **改：** `netdev_max_backlog=65536`、`netdev_budget=1200`、`netdev_budget_usecs=16000`，写入 `/etc/sysctl.d/90-omnihub-net.conf` 持久化。
- **效果：** 同一压测，后端处理 1.74 → 2.39 万/s，软中断 7.6 → 2.1 核，`time_squeeze` 91,807 → 48，重连 8395 → 3，history 零丢点。
- **一次误判与更正：** 起初我把 `softnet_stat` 的"丢包 63 万"当成主因。调参后这个丢包计数几乎没变（约 8400/s → 7500/s），吞吐却大幅改善，说明丢包计数不是主因，起作用的是 `time_squeeze`。**教训：只用"一个现象变了"来证明因果不够，要同时看有没有该变没变的指标。**
- **注意：** 这些是主机级参数，不在仓库里，重新部署不会带上。要写进部署文档的主机要求里。

### B9 Kafka 模式的额外开销

- **背景：** 仓库默认是 `direct`，但测试 VM 的 `.env` 里是 `IOT_INGEST_MODE=kafka`，所以本手册里的容量数字都是 **kafka 模式**的（[README](README.md) 的 15k 基准行也标了这一点）。分析 25k 瓶颈时我才核对到这一点，此前没有逐轮确认过。
- **影响：** Kafka 容器占 1.1～1.3 核，后端多了生产和消费线程，容器之间多两跳网络（后端→Kafka→后端）。Kafka 本身不是瓶颈（消费者每条平均 0.057 ms）。
- **教训：** **压测前先把"当前生效的配置"打印出来核对**（容器里的环境变量 + 后端启动日志），并把它和结果一起记录，而不是默认大家都知道跑的是哪种模式。
- **未验证：** `direct` 模式能省多少，没有测。预期能省出约 2 核，但这只是推测。

### B10 整机 CPU 竞争

- **现象（30k）：** 整机 87% 忙、2 核零散空闲；EMQX 的 Erlang 调度运行队列平均 14、峰值 40。
- **结论：** 没有哪个组件单独卡死，EMQX（4.8 核）、后端（4.2）、Influx（1.9）、Kafka（1.3）、内核（软中断 2.1 + 内核态 2.4）在抢同一批核。
- **改（未做）：** EMQX 搬到独立节点，这是结构性办法，也更接近生产形态。

## 3. 代码层的写法经验

这些是把上面的瓶颈固化成代码习惯的部分：

1. **批量写入一律走 `BatchQueue`/`ShardedBatchQueue`。** `offer()` 永不阻塞，队列满就丢并计数（`dropped_total`），保证 MQTT 回调线程不会被数据库拖住；批大小、flush 耗时都是指标。
2. **按设备 ID 分片，写线程各管一片。** 同一设备永远落在同一个 writer（保序），不同 writer 更新不同的行（没有行锁冲突）。
3. **用 `lingerMs` 凑批，而不是"有就写"。** 目标是批大小与写入快慢解耦。对"一次 HTTP 写一批"的目标（Influx）收益最大。
4. **"最新值"是采样视图，不是审计日志。** 同一属性按时间窗口合并，尾部刷新保证最终一致，迟到的旧值不覆盖新值。
5. **写入线程池与接口线程池分开。** ingest 有自己的连接池（`IngestDb`），突发的页面查询不会饿死写入。
6. **重流量组件用开关，不要硬编码。** 消息日志、history 存储（PG/Influx）、接入模式（direct/kafka）、writer 数、批大小都是配置，压测时改 `.env` 重启单个容器就能做对比。
7. **异步评估、有界队列、丢弃计数。** 告警评估和 WebSocket 推送都在独立线程池，队列满丢弃并计数，不阻塞入库。
8. **新加热路径时同步加分段耗时指标。** 至少要能回答"时间花在构造、网络还是排队"。
9. **配置类按模块拆分，键保持不变。** 每个模块只读自己的设置；用真实 `application.yml` 加非默认环境变量写绑定测试，防止键写错被默认值悄悄掩盖。

## 4. 压测方法的坑

| 坑 | 症状 | 做法 |
|---|---|---|
| 冷启动 | 后端重启后的第一轮出现几十次失败、ack P95 约 9 秒 | 重启后先暖机再测，或把第一轮丢掉；本次暖机复测 0 失败、P95 最大 62 ms |
| 压测机端口耗尽 | `Address already in use`，大量连接失败 | Windows 默认约 1.6 万个临时端口，关闭的连接还要 TIME_WAIT 约 2 分钟。两轮之间等端口释放（可轮询 TIME_WAIT 数量），不要连续跑 |
| 脚本路径 | 压测脚本会切到 `backend` 目录，相对路径被拼成 `backend\backend\...` | 设备文件用绝对路径 |
| 部署的 jar 过期 | 新指标不出现，却以为部署成功了 | 部署用 `-Prebuilt`（VM 用的是预构建覆盖文件）；部署后检查容器里 jar 是否含新类 |
| 计数器生命周期 | 后端重启后 `iot_ingest_processed_total` 归零，EMQX 的不归零 | 一律取**同一时间窗口的差值**再比较 |
| 时区 | VM 本地时间与 UTC 差 8 小时，窗口对不上 | 采样脚本和 Prometheus 查询统一用一个时区，或在分析时换算 |
| 压测机与被测机同物理机 | 互相抢 CPU | 看 VM 的 `steal` 和宿主机占用；压测机自身 CPU 也要看，ack 延迟是在压测端测的 |
| 累计计数混入历史 | `dropped_total` 显示几万，实际是上一轮留下的 | 先记基线值，或只看窗口内的增量 |

## 5. 排查命令速查

```bash
# 整机 CPU 按模式拆（核数 = 占比 × 核总数）：取两次 /proc/stat 第一行相减
head -1 /proc/stat

# 软中断预算：time_squeeze 是第 3 列，dropped 是第 2 列（十六进制，按行求和）
cat /proc/net/softnet_stat
sysctl net.core.netdev_budget net.core.netdev_budget_usecs net.core.netdev_max_backlog

# 各容器 CPU（100% = 1 核）
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'

# PostgreSQL 在等什么
psql -c "select coalesce(wait_event,'-') w, count(*) from pg_stat_activity where backend_type='client backend' group by 1 order by 2 desc"

# EMQX：收发与丢弃、调度队列
curl -s http://localhost:18083/api/v5/prometheus/stats | grep -E '^emqx_(messages_received|messages_sent|delivery_dropped_queue_full|vm_run_queue|connections_count) '

# 后端：批队列与 Influx 分段耗时（Prometheus 查询）
rate(iot_batch_queue_written_total[1m])
iot_batch_queue_dropped_total
sum(rate(iot_influx_write_seconds_sum{phase="http"}[1m])) / sum(rate(iot_influx_write_seconds_count{phase="http"}[1m]))
histogram_quantile(0.95, sum by(le)(rate(iot_batch_flush_seconds_bucket{queue="history"}[1m])))

# 容器里实际生效的接入模式（别信文档默认值）
docker exec <backend> env | grep IOT_INGEST_MODE
```

## 6. 容量速查与上生产的算法

- **上行速率上限（B2）：** `会话数 × broker max_inflight ÷ 确认往返时间`。16 × 4096 在 15k 下 broker 队列为空。
- **history 点数需求：** `msg/s × 每条属性数`。3 万条/s × 7 = 21 万点/s，4 个 writer 实测 19.5 万点/s 已接近上限。
- **你的生产场景：** 1 万台 × 每 5 分钟 1 条 = 约 33 条/s（约 233 点/s），只占压测稳定上限的约 1/850。真正要关心的是下面这些，而不是 TPS：
  1. **集体重连**：后端或 EMQX 重启后 1 万台设备同时重连，每次连接 EMQX 回调后端约 3 次，瞬间约 3 万次认证/授权请求。设备端要带随机退避，EMQX 侧要开授权缓存。这一点没有压过。
  2. **TLS**：压测用的是明文 1883，生产走 8883 会增加握手和内存开销，没有测过。
  3. **单机没有高可用**：VM 挂了接入中断，PostgreSQL 和 Influx 需要备份方案。
  4. **数据增长**：约 233 点/s ≈ 每天 2000 万点，用 Influx 保留策略控制磁盘。

## 7. 已知但没有验证的事

- `direct` 模式比 `kafka` 能多出多少吞吐（B9）。
- 2.8 万条/s 附近真正限制的组件：EMQX 内部调度、还是压测机本身（单进程维持 5000 个连接、每秒收发 3 万条加确认）。
- `softnet_stat` 的丢包计数具体对应哪一跳、为什么调参后没变。
- 软中断在各 CPU 上的分布（没有采逐核数据）。
- TLS 下的连接数和吞吐。
- 1 万台同时重连的恢复时间。
