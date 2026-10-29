# 吞吐优化与认证风暴

> 状态：代码层面的改动有单元测试，并通过了嵌入式 PostgreSQL + 嵌入式 Broker 的集成测试（`DeviceFlowIntegrationTest` 8 项）。**没有做过真实压测，下面不给吞吐数字**；EMQX 与操作系统的调优建议来自通用经验和官方文档思路，未在你的环境验证。

## 1. 问题在哪
改之前，一条属性上报在后端同步做：通信记录 1 次 INSERT、最新值 1 次 upsert、历史 1 次写入（Influx 是 1 次同步 HTTP），再加每个设备每 5 秒 1～2 次状态更新。5000 条/秒 = 每秒上万条数据库语句，处理线程被数据库拖住，MQTT 订阅端消费不过来，EMQX 一侧积压。

## 2. 改了什么
| 项 | 之前 | 现在 |
|---|---|---|
| 通信记录 | 每条消息 1 次 INSERT | 入队，后台线程按批 `INSERT … SELECT unnest(数组)` |
| 最新值 | 每条消息 1 次 upsert | 入队，每批内同设备同属性只留最新一条，一条语句写入 |
| 属性历史 | 每条消息 1 次写入 | **独立队列和线程**；PG 一批一条语句，InfluxDB 每 5000 个点 1 次 HTTP（行协议），失败重试一次 |
| 设备最后通信时间 | 每设备每 5 秒 1～2 次 UPDATE | 内存记录，每 3 秒一条语句批量写；已在线的设备收到消息**零数据库访问** |
| 设备认证 | 首次连接逐个查库 | 启动时（Web 服务开始接收前）把全部设备装入内存，之后连接认证不查库；定时全量刷新；精确失效 |
| MQTT 处理线程 | 固定 8 条 | `max(8, 2×CPU核数)` 可配；队列长度可配 |
| 订阅 QoS | 固定 1 | 可配，0 最快但可能丢 |
| Tomcat | 默认 200 线程 | 默认 400 线程、排队 500（认证/授权回调用） |

批处理队列是“自适应”的：写库期间新消息累积，下一批自然变大，所以负载越高每次写的行数越多、语句数反而不涨。队列满时新数据被丢弃并计数（日志每 5 秒提示一次），**不会阻塞 MQTT 线程**。

批量写入的完整逻辑见 [batch-write.md](batch-write.md)。

## 3. 行为变化（请知悉）
- **落库有延迟**：属性/日志写库延迟通常在百毫秒级，高负载下取决于队列积压。页面实时推送和告警评估不受影响（它们不等落库）。
- **进程崩溃会丢队列中的数据**：正常关闭会写完再退出；`kill -9` 或崩溃时队列里还没写的数据丢失。需要更强保证请用 `kafka` 模式并把 acks 调高。
- **设备最后通信时间**最多滞后约 3 秒（离线判定超时默认 90 秒，不受影响）。
- **通信记录**：`IOT_LOG_OK_PROPERTY=false` 可以不记录成功的属性上报，进一步减负；但仪表盘“上报趋势 / 今日消息数 / 活跃设备”统计依赖这些记录，关闭后这三项会不准。

## 4. 配置项（环境变量）
| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_BATCH_QUEUE` | 200000 | 每个批处理队列的容量 |
| `IOT_BATCH_MAX` | 2000 | 通信记录 / 最新值每批最多行数 |
| `IOT_BATCH_HISTORY_MAX` | 5000 | 历史写入每批最多点数（Influx 建议 5000～10000） |
| `IOT_LOG_OK_PROPERTY` | false | 是否记录成功的属性上报；false = 不写 `iot_device_message_log`（那条表是全量明细，10k msg/s 时约 13 GB/小时，见 §8 / 复盘 §7） |
| `IOT_INGEST_LANES` | 0（自动） | MQTT 处理线程数 |
| `IOT_INGEST_LANE_QUEUE` | 20000 | 每条处理线程队列长度 |
| `IOT_MQTT_SUBSCRIBE_QOS` | 1 | 平台订阅上行 topic 的 QoS |
| `IOT_AUTH_CACHE_WARMUP` / `_REFRESH_SECONDS` / `_MAX` | true / 300 / 200000 | 设备认证缓存（见 `docs/loadtest.md`） |
| `IOT_TOMCAT_THREADS` / `IOT_TOMCAT_ACCEPT_COUNT` | 400 / 500 | 认证/授权回调的处理能力 |

**压测时请把后端日志级别设为 INFO。** 你的 `localext` 里 `com.omnihub` 是 DEBUG，每条 SQL 都会打印，会严重拖慢。

## 5. 认证风暴（大量设备同时连接）
设备连接时 EMQX 会调用后端 `/api/mqtt/auth`，之后每个新主题第一次发布/订阅再调用 `/api/mqtt/acl`（EMQX 有结果缓存）。已经连着的设备持续上报不会再调用。风暴通常出现在：压测一次性连几千个、网络抖动后全部重连、重启 EMQX 或后端。

后端侧（已做）：认证全部走内存，不查库；启动即装载缓存；Tomcat 线程加大。

建议你检查的 EMQX 侧（**未验证**，以你的 EMQX 版本文档为准）：
- HTTP 认证/授权源的**连接池大小**调大（你截图是 8），请求超时 5～10 秒，避免后端偶发慢时整体堆积。
- **开启并调大授权结果缓存**（EMQX 5：授权设置里的 cache，时间可设为数分钟）。
- 监听器的**最大连接数**、**acceptors**、**连接速率限制**（EMQX 5 listener 的 `max_conn_rate` 可限制每秒新连接数，用来给风暴削峰）。
- 压测程序用 `--ramp 50` 起步，逐步加大。

## 5a. 写入并行度与 EMQX 回调的长连接
**写入队列分片**：`latest`（最新值）、`history`（属性历史）、`message-log`（通信记录）三个队列原来各只有一个写入线程，一个线程能写的行数就是上限（压测里 `latest` 约 2.4 万行/秒，之后积压增长到 12.5 万）。现在每个队列拆成几个并行分片，**按设备 ID 哈希**分配：同一台设备始终在同一个分片里（顺序不变），不同分片更新的是不同的行（互不加锁）。

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_BATCH_WORKERS` | 4 | `latest` 的写入线程数（每个线程各占一个数据库连接） |
| `IOT_BATCH_HISTORY_WORKERS` | 2 | `history` 的写入线程数 |
| `IOT_BATCH_LOG_WORKERS` | 2 | `message-log` 的写入线程数 |

总容量（`IOT_BATCH_QUEUE`）平均分给各分片；指标仍是 `iot_batch_queue_*{queue}`，多了 `shard` 标签（看板按 queue 求和，不受影响）。设为 1 就回到单线程。线程数多了会同时占用更多数据库连接（连接池默认 20），也会让 PostgreSQL 的更新更密集，数据库本身的写入能力仍是上限；PostgreSQL 的 `synchronous_commit=off` 能明显提高写入速度，代价是宕机时最多丢最后几百毫秒已提交的数据，没有默认开启。

**EMQX 回调的 HTTP 长连接**：EMQX 用少量长连接、流水线地调用后端的 `/api/mqtt/auth` 和 `/api/mqtt/acl`。Tomcat 默认一个连接处理 100 个请求后就关闭它，在途请求被切断，EMQX 日志里是 `The connection was lost` / `resource not connected`，设备在没有询问后端的情况下被拒绝（NOT_AUTHORIZED）。现在 `server.tomcat.max-keep-alive-requests` 为 -1（不限）、`keep-alive-timeout` 120 秒（`IOT_TOMCAT_MAX_KEEPALIVE_REQUESTS`、`IOT_TOMCAT_KEEPALIVE_TIMEOUT`）。

### PostgreSQL 参数与连接池（压测里实际发生过的停滞）
一次压测里，处理量在 07:51 前后从约 4300 条/秒掉到 400～900 条/秒，持续约 2 分钟：连接池（20）全部占满、等待 6～16 个、获取连接最长等了 3.4 秒，同时 PG 每秒更新的行数从 2.5 万掉到 2000，VM 负载到 30～37（16 核）。8 个 Kafka 消费线程都在等数据库，`latest` 队列涨满后被丢弃了 16.5 万条。PG 当时是镜像默认参数（`shared_buffers` 128 MB、`max_wal_size` 1 GB、每次提交同步刷盘），日志有 `checkpoints are occurring too frequently`，后台写入统计里大部分缓冲区是业务连接自己写的。没有死锁和锁等待，更新基本是 HOT。**停滞当时没有采样，上面是事后证据，PG 参数是不是唯一原因没有证实。**

`docker-compose.yml` 现在用下面的参数启动 PG（`.env` 里可改）：

| 参数 | 默认 | 镜像原默认 |
|---|---|---|
| `PG_SHARED_BUFFERS` | 2GB | 128MB |
| `PG_EFFECTIVE_CACHE_SIZE` | 6GB | 4GB |
| `PG_MAX_WAL_SIZE` | 8GB（另 `checkpoint_timeout=15min`、`wal_buffers=64MB`、`wal_compression=on`） | 1GB / 5min / 4MB / off |
| `PG_MAX_CONNECTIONS` | 200 | 100 |
| `PG_SYNCHRONOUS_COMMIT` | on | on |
| 自动清理 | 15 秒一轮，死元组占 5% 就清（`autovacuum_naptime=15s`、`scale_factor=0.05`、`cost_limit=2000`） | 1 分钟 / 20% |
| 磁盘假设 | SSD（`random_page_cost=1.1`、`effective_io_concurrency=200`） | HDD |

`IOT_DB_POOL`（后端连接池）默认 40。**`PG_SYNCHRONOUS_COMMIT=off` 没有默认开启**：它能明显加快每次提交，代价是 PG 宕机时最多丢最后几百毫秒已提交的数据（不会损坏数据库）；压测想看写入上限时可以打开。**`IOT_LOG_OK_PROPERTY=false`** 也是压测的常用开关：每条属性上报都写一行通信记录（压测里一小时 530 万行、1.9 GB），关掉后这部分写入消失，代价是看板的"今日消息"和趋势会偏少。

## 5b. EMQX 对平台订阅的限制（几千条/秒以上要调）
平台只用**一个** MQTT 连接订阅全部设备消息。EMQX 默认对每个订阅者：未确认的 QoS 1 窗口 32、排队上限 1000、连接进程邮箱上限 1000、进程堆上限 32 MB。设备消息到达得比后端确认得快时，队列和邮箱先满，EMQX 就丢投递（`emqx_delivery_dropped_queue_full`），邮箱超限时直接关闭这个连接（EMQX 日志 `mailbox_overflow`，后端日志 `MQTT gateway connection lost`）。一万设备压测里在约 3000～7000 条/秒时出现过。

`docker-compose.yml` 里用环境变量调大了（`.env` 里可改，默认值如下）：

| 变量 | 默认 | EMQX 原默认 |
|---|---|---|
| `EMQX_MAX_INFLIGHT` | 4096 | 32 |
| `EMQX_MAX_MQUEUE_LEN` | 100000 | 1000 |
| `EMQX_MAX_MAILBOX_SIZE` | 100000 | 1000 |
| `EMQX_MAX_HEAP_SIZE` | 512MB | 32MB |
| `EMQX_TCP_BACKLOG` | 4096（不要超过主机 `net.core.somaxconn`） | 1024 |

- 这些值对**所有**客户端生效；设备一般只有自己的下行主题，影响很小。队列满时每条消息占用内存，最坏情况每个订阅者多占几十 MB。
- 用环境变量设置的值优先于 EMQX 控制台里的修改；要在控制台里调，先删掉 compose 里对应的变量。
- 核对是否生效：`curl -s -u … http://<主机>:18083/api/v5/configs/mqtt` 里的 `max_inflight`、`max_mqueue_len`，以及 `…/configs/force_shutdown`。
- **这只是放宽限制，不是根治**：所有消息仍经过同一个订阅连接，后端对这个连接的处理速度仍是上限。根治需要共享订阅（`$share/<组>/…`）加多个消费连接并行，也是后端多实例的前提，目前没有实现。

## 6. 操作系统（EMQX 所在虚拟机）
- 文件句柄：`ulimit -n` 至少 `连接数×2`，EMQX 官方建议很大的值（如 1048576），并确认 EMQX 进程实际生效（`cat /proc/<pid>/limits`）。
- `net.core.somaxconn`、`net.ipv4.tcp_max_syn_backlog` 适当调大；`net.ipv4.ip_local_port_range` 扩大（压测机）。
- 内存：几千连接加 QoS1 队列，虚拟机建议 ≥ 4GB；用 `dmesg | grep -i oom` 确认有没有被系统杀掉。

## 7. 仍然存在的限制
- 平台只用**一个** MQTT 连接订阅全部上行，EMQX 对 QoS1 单个订阅者有 inflight 窗口（默认 32）。网络往返较大时这会成为上限：可把 `IOT_MQTT_SUBSCRIBE_QOS=0` 试验，或后续改成多连接的共享订阅（`$share/组/…`，需要 EMQX 端配合保证同设备有序，本次没有做）。
- 通信记录仍是“每条消息一行”，量很大时表增长很快；清理由每日任务按保留天数**分批**删除（每批 1 万行）。
- 单实例设计：队列、去重缓存、告警连续计数都在内存里。

## 8. 本轮改动：把“每条消息的写库”和同步副作用移出处理线程
 
第 5a / 5b 节的实测（约 4300 条/秒时 PG 每秒 2.5 万行更新、连接池打满、`latest` 单线程约 2.4 万行/秒、EMQX 在 3000～7000 条/秒开始丢投递）说明瓶颈不是 MQTT 收包，而是**每条消息在后端放大的写库次数**，以及**在 MQTT 处理线程上同步做的副作用**（告警 UPDATE、WebSocket 序列化与 socket 写）。这一轮的改动都围绕这两点，没有新增基础设施。
 
| # | 之前 | 现在 | 关闭新行为 |
|---|---|---|---|
| 1 最新值 | 每条属性上报 upsert 一行 | 同一设备的同一属性**每 `IOT_LATEST_MIN_INTERVAL_MS` 最多写一次**；跳过的样本留在内存（只留最新值），由后台尾部补偿写入，最多滞后一个间隔 | `IOT_LATEST_MIN_INTERVAL_MS=0` |
| 2 连接池 | 写入、HTTP、看板查询共用 `IOT_DB_POOL` | 新增独立 `ingest` 池（`IOT_INGEST_DB_POOL`，默认 12），批量写入 / 最新值 / 历史 / 通信记录 / 最后通信时间 / 告警评估都走它；主池留给 HTTP。慢查询再也不能抢走写入线程的连接 | `IOT_INGEST_DB_POOL=0`（共用主池） |
| 3 告警 | 每条上报在每个 MQTT 线程上查规则、可能 UPDATE `alarm_record` | 规则常驻内存（按设备/产品索引，默认 30 秒刷新一次，改规则后立即重载）；评估在**单工作线程**上排队；`trigger_count` / `last_triggered_at` 在内存累加、**每秒合并成一条 `UPDATE … FROM unnest(...)`**；没有 ACTIVE 告警时的恢复判断不发 SQL；设备没有规则时零开销（不查库、不分配） | `IOT_ALARM_FLUSH_MS` 调小不回到旧行为；旧行为是每条消息一次 UPDATE |
| 4 WebSocket | 在 MQTT 线程上序列化 JSON，并在 `synchronized(session)` 里同步写 socket（慢浏览器会拖住处理线程） | 交给 `ws-send` 小线程池；同一设备的属性推送在 `IOT_WS_COALESCE_MILLIS`（默认 200ms）窗口内**只发最新一条**；状态/事件/命令/告警走“立即”通道不合并；队列满则丢弃并计数，绝不阻塞 ingest | `IOT_WS_COALESCE_MILLIS=0`（每条都推） |
| 5 看板 | 每次请求都扫 `iot_device_message_log`（一天几百万行），每个打开的浏览器标签各扫一遍 | 聚合结果按 租户+时间桶+参数 缓存 `IOT_DASHBOARD_CACHE_SECONDS`（默认 10 秒）；`recentDevices` 不缓存 | `IOT_DASHBOARD_CACHE_SECONDS=0` |
| 6 定时任务 | 所有 `@Scheduled` 共用一个线程，清理/业务指标变慢就推迟离线扫描和最后通信时间落库 | `IOT_SCHEDULER_THREADS`（默认 4） | 设 1 |
| 7 保留期清理 | 一条 DELETE 删几个月的数据（长锁 + 巨大 vacuum 积压） | 每批 1 万行、批间 50ms，历史表与事件表同理 | — |
| 8 处理队列满 | 固定 `CallerRunsPolicy`（回到 Paho 回调线程，压 broker） | 默认仍是 block（不丢消息）；`IOT_LANE_OVERFLOW=drop` 改为丢弃并计入 `iot_ingest_lane_dropped_total`，保证 broker 连接不被拖垮 | 默认即旧行为 |

新增的环境变量（`docker-compose.yml` 已透传，默认值与代码一致）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_LATEST_MIN_INTERVAL_MS` | 1000 | 同一设备同一属性的最新值最小写入间隔（ms）；0 = 每条都写 |
| `IOT_INGEST_DB_POOL` | 12 | ingest 专用连接池大小；0 = 共用 `IOT_DB_POOL` |
| `IOT_INGEST_DB_TIMEOUT_MS` | 2000 | 批量写入等连接的上限，超时按丢弃处理并计数 |
| `IOT_ALARM_RULE_REFRESH_SECONDS` | 30 | 规则内存索引刷新周期 |
| `IOT_ALARM_QUEUE_CAPACITY` | 50000 | 告警评估队列容量（满了丢弃评估） |
| `IOT_ALARM_FLUSH_MS` | 1000 | `trigger_count` 合并落库周期（ms） |
| `IOT_WS_SENDER_THREADS` / `IOT_WS_QUEUE_CAPACITY` | 2 / 20000 | WebSocket 发送线程数与队列 |
| `IOT_WS_COALESCE_MILLIS` | 200 | 同设备属性推送合并窗口（ms）；0 = 每条都推 |
| `IOT_DASHBOARD_CACHE_SECONDS` | 10 | 看板聚合缓存时间；0 = 每次查询 |
| `IOT_SCHEDULER_THREADS` | 4 | `spring.task.scheduling.pool.size` |
| `IOT_LANE_OVERFLOW` | block | 处理队列满时：block（背压）/ drop（丢弃并计数） |
| `IOT_MQTT_INGEST_CONNECTIONS` | 16 | 共享订阅的上行连接数；1 = 单连接（broker 不支持 `$share` 时） |
| `IOT_MQTT_MAX_INFLIGHT` | 4096 | 每条上行连接的在途窗口（Paho `maxInflight`）；必须 ≤ `EMQX_MAX_INFLIGHT`，两者取小 |
| `IOT_MQTT_SHARED_GROUP` | ingest | 共享订阅组名（`$share/<group>/<filter>`） |
| `EMQX_SHARED_SUBSCRIPTION_STRATEGY` | hash_clientid | EMQX 分发策略；按发布者 clientid 哈希，保证同一设备固定落到一条连接。映射到 `mqtt.shared_subscription_strategy`（EMQX 5.7+） |
 
### 8.1 行为变化（请知悉）
- **最新值最多滞后约 1 秒**（尾部补偿保证最后一条一定落库）。最新值是"当前快照"，不是审计数据；历史库不受影响（仍然每条都写）。
- **告警连续触发计数与 ACTIVE 集合在内存里**：进程重启后 `consecutive` 归零（需要连续 N 次的规则重新计数），ACTIVE 告警在启动时从库里恢复。`trigger_count` 落库最多滞后 `IOT_ALARM_FLUSH_MS`（默认 1 秒）。
- **告警评估队列满时会丢弃评估**而不是阻塞 ingest，丢弃数量见 `iot_alarm_eval_dropped_total`。默认容量 5 万，正常负载下不会触发。
- **WebSocket 属性推送会合并**：200ms 内同一设备的多次属性上报，前端只收到最后一条（`type=property`）；状态、事件、命令、告警不合并。
- **看板数字最多滞后 10 秒**。
 
### 8.2 新增指标
| 指标 | 含义 |
|---|---|
| `iot_alarm_eval_queue_pending` | 等待评估的告警事件数 |
| `iot_alarm_eval_dropped_total` | 因工作线程跟不上而丢弃的评估数（>0 说明该调大 `IOT_ALARM_QUEUE_CAPACITY` 或减少规则/设备数） |
| `iot_ws_push_pending` | 等待写出的 WebSocket 推送数 |
| `iot_ws_push_dropped_total` | 因发送队列满而丢的推送数（>0 说明有很慢的客户端） |
| `iot_ingest_lane_dropped_total` | `IOT_LANE_OVERFLOW=drop` 时丢弃的上行消息数 |
 
### 8.3 怎么看有没有效果
1. 压测同时记录：后端 `iot_ingest_*` / `iot_batch_queue_*` 指标、PG 的 `pg_stat_database.xact_commit` 与 `pg_stat_user_tables.n_tup_upd`、`dmesg`/负载。
2. 关键判据：同样 TPS 下 **PG 每秒行更新数应明显下降**（最新值采样 + 告警合并），**连接池等待时间应接近 0**（`hikaricp_connections_pending`、`hikaricp_connections_acquire_seconds`），`iot_lane_queue_pending` 不再持续增长。
3. 如果 `iot_alarm_eval_dropped_total` 或 `iot_ws_push_dropped_total` 持续增长，说明相应队列太小或消费端太慢，而不是数据库问题。
4. `IOT_LATEST_MIN_INTERVAL_MS` 调到 5000 能进一步减少写入，但"当前值"最多滞后 5 秒；看板上看到的当前值会相应变旧。
 
### 8.4 回归测试
`backend` 下 `mvn test`：126 项全部通过（含 `DeviceFlowIntegrationTest` 8 项：上报 → 最新值/历史 → 告警触发与恢复 → 看板概览）。本轮没有做真实压测，上面仍不给吞吐数字。

### 8.5 上行入口：多连接共享订阅（`$share/`）
单连接 = 单个 broker 会话 = 单个投递队列 + 单个 Paho 回调线程。压测中平台只用一条连接订阅全部上行，**约 6k msg/s 就触顶**：EMQX 该会话的 mqueue（`max_mqueue_len=100000`）先满，日志出现每分钟 7–30 万条 `dropped_msg_due_to_mqueue_is_full`，随后 `mailbox_overflow` 直接断开平台连接（后端日志 `MQTT gateway connection lost`）。此时后端自己的计数（lane/batch/forward 丢弃、连接池 pending）全是 0——消息根本没到后端。

现在网关开 `IOT_MQTT_INGEST_CONNECTIONS` 条连接，全部订阅同一个共享订阅：

```
$share/ingest/iot/+/+/property/post
$share/ingest/iot/+/+/event/post
$share/ingest/iot/+/+/status
$share/ingest/iot/+/+/command/reply
```

- 每条消息只投递给组内**一条**连接（不是广播），所以多连接是分摊而不是复制。
- 每个会话有自己的 mqueue，总缓冲随连接数线性增长；每条连接也各有一个回调线程。
- EMQX 分发策略设为 `hash_clientid`：同一台设备的固定落到同一条连接，保序；下游 Kafka 仍按 `productKey.deviceKey` 做 key，分区内有序。
- **这个键在 EMQX 5.7+ 的位置变了**：配置路径是 `mqtt.shared_subscription_strategy`，容器里对应
  `EMQX_MQTT__SHARED_SUBSCRIPTION_STRATEGY`。写成旧的 `EMQX_BROKER__SHARED_SUBSCRIPTION_STRATEGY` 会被
  **静默忽略**（`emqx ctl conf show broker` 里根本没有这一项），实际仍是 `round_robin`——多连接下发是乱序的。
  验证：`docker compose exec emqx emqx ctl conf show mqtt | grep shared_subscription_strategy`。
- broker 拒绝 `$share`（老版本 / 未开启）时，网关**自动降级为单连接**并打一条 error，不会变成 N 份重复投递。
- 新指标：`iot_mqtt_gateway_connections`（已连接数）、`iot_mqtt_gateway_connections_expected`（配置数）。
- 注意：设备侧仍然是一条连接一台；这里扩的是**平台收上行**的连接数。

### 8.6 新指标
| 指标 | 含义 |
|---|---|
| `iot_mqtt_gateway_connections` | 当前已连接的上行连接数 |
| `iot_mqtt_gateway_connections_expected` | 配置的上行连接数 |

### 8.7 最新值：一台设备一行（Flyway V3）
`iot_device_latest` 原来是 `(device_id, identifier)` 一行，而一条属性消息带 7 个属性 → **一条消息 = 7 行 UPDATE**。
"1 万台 × 1 条/秒" 需要 7 万行更新/秒，实测 PostgreSQL 只能到约 2.2 万/秒：最新值队列堆到 5 万+，Kafka 消费被拖慢，
随后 EMQX 又开始丢消息（后端 CPU 却只有 1.5 个核 —— 典型的写库反压）。

V3 迁移改成每台设备一行：

```sql
CREATE TABLE iot_device_latest (
    device_id BIGINT PRIMARY KEY,
    values    JSONB  NOT NULL   -- {"temperature": {"v": 21.5, "t": 1793179493731}, ...}
);
```

- 一条消息只产生 **1 行更新**（7× → 1×），WAL 也少约 7 倍。
- `t` 保留每个属性的上报时间，接口仍按属性返回 `reportedAt`；UPSERT 时逐属性比较 `t`，迟到的旧值仍不会覆盖新值。
- Flyway 启动时自动把旧数据聚合过去（`jsonb_object_agg`），`/devices/{id}/latest` 的返回结构不变。
- 注意：最新值已经不再"每条都写"（采样 + 每设备一行），它只是当前快照；审计数据仍看历史库。

### 8.8 写库侧：WAL 刷盘与批次 linger

修完 8.5/8.7 后，"1 万台 × 1 条/秒"仍然卡在约 2.6k msg/s，但后端线程栈全是空闲（CPU 0.6 核），PG 却 300–500% 且 `pg_stat_activity.wait_event` 反复是 `WALWrite`。两个原因叠加：

1. **每批 commit 都在等 WAL 落盘。** 只对 ingestion 连接池设置 `SET synchronous_commit = off`（`iot.ingest.synchronous-commit`，取值 `off|local|remote_write|remote_apply|on`，默认 `off`）。代价是操作系统崩溃时最后几百毫秒的最新值/消息日志可能丢失，数据库本身一致；`on` 可恢复旧行为。
2. **批次太小。** 批量 = 到达速率 × 上一次写入耗时：写入越慢 → 批越小 → 每行摊的固定开销越大。`BatchQueue` 新增 `lingerMs`（`iot.ingest.batch.linger-ms`，默认 50）：第一批到达后继续攒 50ms 再写。latest 从 135 行/24ms 变成 981 行/36ms，history 从 221 点/15ms 变成 2226 点/28ms。

结果（同档位 10k×1、120s）：后端处理 2.6k → 9.4k msg/s，客户端 `failed` 34 204 → 0，ackP95 9.8s → 19ms，EMQX `delivery_dropped_queue_full` 不再增长，history 队列丢弃 88.8 万行 → 0。新指标：`iot_batch_size`（每批行数）与 `iot_batch_flush_seconds`（每次写入耗时），两个一起看才知道是"批太小"还是"库太慢"。

完整的定位过程、七步排除法与前后对比图见 [`docs/perf-ingest-retro.md`](perf-ingest-retro.md)。

**但 9.4k/s 只在"关消息日志 + 多连接"下成立。** 关日志后 8 条连接同样跑满同一档位（`failed=0`、ackP95 17ms、三个批队列零丢弃、latest 33.6ms/批、history 24.2ms/批），8 与 16 等价；默认的 4 条是否够用没测。恢复成**当时**的出厂默认（`IOT_LOG_OK_PROPERTY=true`、4 条连接）复测同一档位只有 **2.0k msg/s**、客户端失败 39 990 条：瓶颈换成了 `iot_device_message_log`——每行 1 次堆写 + 3 次索引写 + payload，实测上限约 4.2k 行/s（166.8 行/79.75 ms × 2 线程），且 3 小时就攒到 1634 万行 / 6.2 GB。这条表是全量明细，10k msg/s 时约 13 GB/小时，靠调参解决不了，只能关掉、采样、或把明细移出 PostgreSQL。详见复盘的 §7。

### 8.9 上行侧：每条连接的在途窗口

关掉消息日志后，10k 档位在 8 条连接下跑通（零失败），但把速率提到 12k / 15k 就崩了：平台会话的
`inflight` 精确顶在 `8192 = 8 × 1024`（EMQX 每会话默认 `max_inflight`），单会话 mqueue 涨到 6.7 万、合计 40 万，
而同期的 PostgreSQL（等待事件只有 `ClientRead`）、三个批队列（pending/drops 全 0）和 Kafka 生产者
（`buffer_exhausted_total=0`、`waiting_threads=0`）全部空闲。吞吐的实际公式是：

```
平台消费能力 ≈ 连接数 × 每连接在途窗口 ÷ 单条消息的确认往返
```

所以要同时动两个乘数（下表的"默认"一列已经是调好后的仓库默认值）：

| 位置 | 变量 | 默认 | 15k 档实测值 |
|---|---|---|---|
| 平台（Paho） | `IOT_MQTT_MAX_INFLIGHT` | 4096 | 4096 |
| broker（EMQX） | `EMQX_MAX_INFLIGHT` | 4096 | 4096（**必须 ≥ 上面那个**） |
| 平台 | `IOT_MQTT_INGEST_CONNECTIONS` | 16 | 16 |

`IOT_MQTT_MAX_INFLIGHT` 是新增项（`iot.mqtt.max-inflight`），单边调大没有效果，**两边取小**。
仓库默认已按 15k 档调好（`.env.example`、`docker-compose.yml`、`application.yml` 三处一致）；
小部署可以降到 4 × 1024，两条都在 `docs/perf-ingest-retro.md` §8.6 的配置表里。

实测（5 000 台 × 3 条/秒 = 15k msg/s，120s，16×4096）：EMQX 上 mqueue **全程为 0**、总在途只用到 4 879/65 536，
后端处理 1 574 498 条 = EMQX `messages_sent` 1 574 498 条，broker 侧零丢弃；同样的负载在 8×1024 下后端只能到 4.6k/s。

窗口放大也放大了下游的账：这一档 `history` 队列（InfluxDB）丢弃 153 万点，因为 15k × 7 属性 = 105k 点/s，
而 2 个 writer 每批 4 396 点 / 98.32 ms 只能写约 89k/s。要跑 15k 需要 `IOT_BATCH_HISTORY_WORKERS=4`
（理论上限约 180k/s，代价是 InfluxDB 压力翻倍）；`latest` 62k 行/s 对 70k 需求也只剩一点余量，靠
`IOT_LATEST_MIN_INTERVAL_MS` 的采样撑着。

排查提示：这种情况**不要去看数据库**。判断依据是"哪一层在积压"——下游全干净、只有 broker 的会话队列在涨，
答案就只能是窗口与确认往返。完整的 12k/15k 两档数据见复盘的 §8。


