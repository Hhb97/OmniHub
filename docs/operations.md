# 运维与监控

本文覆盖：看什么指标、积压怎么定位、已知行为、故障排查、安全注意事项。

## 1. 监控栈

```
后端 /actuator/prometheus ──────┐
EMQX /api/v5/prometheus/stats ──┤
InfluxDB /metrics ──────────────┼──► Prometheus ──► Grafana（4 个看板）
postgres-exporter ──────────────┤                    └─ 另有 InfluxDB 数据源
kafka-exporter ─────────────────┤
node-exporter（主机）───────────┘
```

| 服务 | 作用 | 端口 |
|---|---|---|
| prometheus | 每 15 秒采集，保留 `PROMETHEUS_RETENTION`（默认 15 天） | 不对外 |
| grafana | 看板，自动加载数据源和看板 | `GRAFANA_PORT`，默认 3000 |
| postgres-exporter | PostgreSQL 指标 | 不对外 |
| node-exporter | 主机 CPU、内存、磁盘、网络、TCP 连接 | 9100，主机网络，**只允许可信机器** |

登录 Grafana：`http://<主机IP>:3000`，用户 `admin`，密码在主机 `.env` 的 `GRAFANA_ADMIN_PASSWORD`。首页是"吞吐与消息积压"。

**安全**：`/actuator/prometheus` 需要 `Authorization: Bearer <IOT_METRICS_TOKEN>`；令牌为空时端点关闭；登录 JWT 不能读它。Prometheus 不对外开放。

临时查询：

```bash
docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up'
```

## 2. 看板与积压判断

### 2.1 四个看板

**① 吞吐与消息积压**（最重要）

- 顶部：当前吞吐、平均/P95 排队延迟、消息积压总量、丢弃速率、平台 MQTT 连接数。
- 上行：按类型（属性/事件/状态/应答）、被拒绝的消息（按原因）、延迟分位数、lane 队列。
- 批量写入：三个队列（latest/history/message-log）的积压、使用率、丢弃、写入速率、平均每批条数、单批 P95 耗时。
- EMQX 回调：认证/授权 QPS、P95 延迟、失败（401/5xx）。**连接风暴先看这里。**
- Kafka（仅 kafka 模式）：消费积压、写入/消费速率、broker 数、各分区积压、转发失败丢弃。

**② 中间件**：采集目标 UP/DOWN；Kafka（broker、分区、消费组、保留量、各分区速率与积压）；EMQX（连接数、订阅数、收发速率、连接/断开速率、丢弃、认证失败）；PostgreSQL（连接、事务、行写入、缓存命中率、库大小、HikariCP）；InfluxDB（进程 CPU/内存/goroutines/文件句柄，以及后端写 Influx 的批量耗时）。

**③ 系统与 JVM**：主机 CPU/内存/负载/磁盘/网络/TCP 连接与 TIME_WAIT/文件句柄；后端堆、GC、线程、Tomcat 忙线程、HTTP 速率与延迟。

**④ 业务概览**：在线/离线/未激活/已禁用设备数、总数、在线率、上下线速率、数据库与内存在线数对比、各产品在线数、认证缓存条数、页面实时连接数、活跃/未确认/24h 告警、启用规则、在途指令、上行速率。

### 2.2 消息会在这几处排队

| 位置 | 指标 | 说明 |
|---|---|---|
| EMQX → 平台 | EMQX 看板的"丢弃消息 / 投递队列满"；`emqx_messages_dropped` | 平台消费不过来时先丢；**这种积压看不到队列长度，只能看丢弃数** |
| EMQX 会话 | 各会话 `inflight` / `mqueue` | 窗口用满 → mqueue 涨；mqueue 也满 → 丢 |
| 后端 lane | `iot_lane_queue_pending`、`iot_lane_queue_max` | direct 模式；最满的 lane 接近 20 000 就会拖慢接收 |
| Kafka | `kafka_consumergroup_lag{topic="iot-uplink"}` | kafka 模式；后端客户端 `kafka_consumer…records_lag_max` 作补充 |
| 批量写队列 | `iot_batch_queue_pending{queue}` | 等待写 PG/Influx 的条数，最常见的积压位置 |
| 丢失 | `iot_batch_queue_dropped_total`、`iot_ingest_forward_dropped_total`、`iot_ingest_lane_dropped_total` | **应为 0** |

### 2.3 判断顺序

1. **丢弃 > 0** → 已经在丢数据，先降负载或加速下游。
2. **批量写队列持续上升**、平均每批接近上限、单批耗时变长 → PG/Influx 写不动，看中间件看板和磁盘 IO。
3. **lane 积压** → 后端 CPU 不够或某环节慢，看系统与 JVM。
4. **EMQX 会话 inflight/mqueue 涨，但后端所有队列都空** → 窗口/确认往返问题，不是数据库；看 [ingest.md](ingest.md) §3。
5. **回调 QPS 高、延迟高、有 401/5xx** → 连接风暴或回调配置有问题。
6. **P95 排队延迟升高但队列不大** → kafka 模式下看 Kafka 往返和 lag。

> 比较后端与 broker 的计数时注意窗口：后端重启后 `iot_ingest_processed_total` 归零，EMQX 的 `messages_sent` 跨重启保留。必须取**同一时间窗口的差值**，否则会得出"后端多收/少收"的错误结论。

### 2.4 后端指标（节选）

| 指标 | 含义 |
|---|---|
| `iot_ingest_processed_total` | 进入处理器的上行消息总数，`rate()` 即吞吐 |
| `iot_uplink_messages_total{type}` | PROPERTY / EVENT / STATUS / REPLY |
| `iot_uplink_rejected_total{reason}` | unknown_device / disabled / oversize / bad_json / unsupported_topic |
| `iot_ingest_delay_ms_*` | 从收到 MQTT 到开始处理的耗时，不含入库 |
| `iot_lane_queue_pending` / `_max` | MQTT 处理 lane 队列 |
| `iot_batch_queue_pending` / `_capacity{queue}` | 批量写队列积压与容量 |
| `iot_batch_queue_written_total` / `_dropped_total{queue}` | 已写入 / 丢失 |
| `iot_batch_size_*{queue}` | 每批条数（`sum/count` 为平均） |
| `iot_batch_flush_seconds_bucket{queue}` | 一次批量写的耗时 |
| `iot_mqtt_gateway_connections` / `_expected` | 已连接 / 配置的上行连接数 |
| `iot_alarm_eval_queue_pending`、`iot_alarm_eval_dropped_total` | 告警评估积压 / 丢弃 |
| `iot_ws_push_pending`、`iot_ws_push_dropped_total` | WebSocket 推送积压 / 丢弃 |
| `iot_devices{status}`、`iot_product_devices{...}` | 设备数（所有租户合计） |
| `iot_alarms_active{level}`、`iot_alarms_unacknowledged`、`iot_alarms_triggered_24h` | 告警 |
| `iot_commands_in_flight{status}` | 在途指令 PENDING / SENT / RECEIVED |
| `iot_device_status_changes_total{to}` | 上线/下线事件，`rate()` 即连接抖动 |
| `iot_devices_online_in_memory`、`iot_device_auth_cache_entries`、`iot_ws_sessions` | 内存在线数、认证缓存、页面前端连接 |
| `http_server_requests_seconds_*{uri,status}` | 含 `/api/mqtt/auth`、`/api/mqtt/acl` |
| `jvm_*`、`tomcat_*`、`hikaricp_*`、`process_*` | Spring Boot 自带 |

业务指标由后端每 15 秒在后台查一次库后更新内存，Prometheus 抓取时不查库，所以最多滞后 15 秒。

## 3. 已知行为

### 3.1 broker 重启后约 2 分钟才恢复满窗口

实测（16 连接 × 4096，EMQX 5.7.2 原地重建容器）：

```
03:15:59  16 条连接同时丢失
03:16:04  conn 3   subscribed        ← 第一次重试成功
03:16:08  conn 15  (+4s)
03:16:16  conn 6   (+8s)
03:16:32  conn 12  (+16s)
03:17:04  conn 11  (+32s)
03:18:08  conn 10, 8, 13, 2, 16, 1, 7  (+64s，7 条一起回来)
```

**共 129 秒**才回到 16/16。原因：

- `MqttGateway` 打开了 Paho 的 `automaticReconnect`，但没设 `maxReconnectDelay`，默认 128 秒封顶，退避是 1→2→4→8→16→32→64→128 秒；
- 所有订阅动作排在单线程的 `mqtt-connector` 上，所以是"逐条回来"而不是一起回来。

影响：恢复期间有效窗口从 16 × 4096 掉到最低 5 × 4096，ingest 容量约剩 1/3。EMQX 每条会话 mqueue 上限 10 万条，5 条合计约 50 万条——**15k msg/s 下只相当于 33 秒的缓冲，而恢复要 129 秒**，所以满载时重启 broker 会真的丢消息。

当前**未改**。如需缩短，在 `MqttGateway.createClient` 里加：

```java
opts.setMaxReconnectDelay(5_000);   // Paho 默认 128s
```

预期从 129 秒降到约 25 秒。验收方式：重建 EMQX，然后看 `iot_mqtt_gateway_connections` 多久回到 16。

### 3.2 最新值最多滞后约 1 秒

`IOT_LATEST_MIN_INTERVAL_MS=1000` 会采样；尾部补偿保证最后一条落库。最新值是"当前快照"，不是审计数据；历史库每条都写。

### 3.3 告警的连续计数在内存里

`AlarmEngine.consecutive` 进程重启后归零，需要连续 N 次的规则重新计数；ACTIVE 告警启动时从库里恢复。`trigger_count` / `last_triggered_at` 落库最多滞后 `IOT_ALARM_FLUSH_MS`（默认 1 秒）。告警评估队列满时丢弃评估而不是阻塞 ingest，见 `iot_alarm_eval_dropped_total`。

### 3.4 WebSocket 推送会合并

200 ms 内同一设备的多次属性上报，前端只收到最后一条（`type=property`）；状态、事件、命令、告警不合并。慢客户端导致的丢弃见 `iot_ws_push_dropped_total`。

### 3.5 看板最多滞后 10 秒

`IOT_DASHBOARD_CACHE_SECONDS=10`。每个打开的浏览器标签都会轮询这些查询，缓存是为了避免打爆数据库。

### 3.6 通信记录默认关闭

`IOT_LOG_OK_PROPERTY=false` 时不记录"成功的属性上报"，只记失败、事件、状态、指令、下行。仪表盘的"上报趋势 / 今日消息数 / 活跃设备"统计这些记录，所以会偏低。打开它会让 ingest 上限降到约 4.2k msg/s，并产生约 13 GB/小时的数据。

### 3.7 设备在线状态有两层

- `mqtt_connected`：传输层，靠 broker 事件 / `status` 主题 / 任意上行；
- `online_status`：业务层，90 秒没有上行就置 OFFLINE，即使 MQTT 还连着。

外部 broker 没有连接事件回调，所以设备"断开"主要靠遗嘱和 90 秒超时发现，不是即时的。

### 3.8 登录锁定与 JWT 黑名单在内存里

仅单实例有效；重启后清空。

## 4. 故障排查

### 4.1 通用

| 现象 | 原因与处理 |
|---|---|
| 后端启动报 `Port 8080 was already in use` | 端口被占用。`SERVER_PORT=8081`，前端用 `VITE_DEV_BACKEND=http://localhost:8081` |
| 启动报 `Gave up waiting for server to start`（嵌入式 PG） | 上次后端被强杀，残留 `postgres.exe` 占着 15432。结束这些进程（路径含 `embedded-pg`），必要时删 `backend/data/pgdata/postmaster.pid` |
| 启动报 `iot.security.jwt-secret ... must be set` | 非 `local` profile 必须设 `IOT_JWT_SECRET`（≥ 32 字符） |
| 登录不了 / 没有管理员 | 管理员只在用户表为空时按 `IOT_ADMIN_PASSWORD`（≥ 8 位）创建；日志出现 "no administrator was created" 说明没设。已有用户时改变量无效，用用户管理重置密码 |
| 登录返回 429 | 同一用户名 5 分钟内失败 5 次被临时锁定，等 5 分钟（重启也清除） |
| 前端 401 循环跳登录 | Token 过期或被退出；重新登录 |
| 前端 WebSocket 一直断开 | 开发时 `/ws` 走 Vite 代理；跨域部署设 `IOT_CORS_ORIGINS`；Nginx 需要 `Upgrade` 头（`deploy/nginx.conf`） |
| 仪表盘"今日"不对 | 调整 `IOT_DASHBOARD_ZONE`（默认 UTC） |
| 登录返回 `Invalid CORS request`（403） | nginx 转发时 Host 头丢了端口。`deploy/nginx.conf` 已用 `$http_host`；换代理时要保证 Host（含端口）原样转给后端，或设 `IOT_CORS_ORIGINS` |

### 4.2 设备接入

| 现象 | 原因与处理 |
|---|---|
| 设备连不上（NOT_AUTHORIZED） | 先跑 `sh deploy/emqx-bootstrap.sh`；看后端日志有没有 `Broker hook rejected`（令牌不一致）；EMQX Dashboard 认证页确认 HTTP 认证是"已连接" |
| 检查 clientId / 用户名 | clientId = username = `productKey.deviceKey`；密钥是否最新（轮换后旧密钥失效）；设备/产品是否启用 |
| 连上了但页面显示离线 | 业务在线要求超时内有上行。先发 `status` online 并周期上报；检查 `device.offline.timeout.seconds` |
| 后端一直 `MQTT gateway cannot connect` | EMQX 还没配好回调（平台自己的 `platform` 账号也走回调）；运行 bootstrap 脚本 |
| 设备连得上但收不到指令 | EMQX 授权源没生效，或 `no_match` 仍是 allow / ACL 回调报错 |
| EMQX 里有很多断开的 `iot-platform-…` 客户端、`emqx_delivery_dropped_queue_full` 在涨 | 旧版本的持久会话残留。现在默认 `IOT_MQTT_CLEAN_SESSION=true` 且 client id 每次随机；残留可用 `DELETE /api/v5/clients/<clientid>` 清除 |
| 模拟器连不上 | `simulator/config.local.json` 由 `npm run provision` 生成；重新 provision 会轮换密钥，旧模拟器需重启 |

### 4.3 数据与告警

| 现象 | 原因与处理 |
|---|---|
| 数据没入库 | 看设备"通信记录"：`INVALID` 行写明原因（未定义属性、超范围、JSON 错误、超 16KB）。确认物模型已保存 |
| 历史曲线为空但最新值有 | `history` 队列写失败。看 `docker compose logs backend | grep -i influx`，确认 `IOT_HISTORY_STORE` 和 `IOT_INFLUX_*`；启动时应有 `InfluxDB history store: <url> database ...` 和 `database 'iot' ready` |
| 看板"丢弃"持续 > 0，日志 `Batch queue 'history' failed to write ... InfluxDB write failed` | 历史写 Influx 失败，数据已丢。看异常类型（日志每分钟打一次完整堆栈）。Influx 的 `_internal` 库 `httpd.writeReq` 一直为 0 说明请求没到达 |
| 指令返回 409 | 设备离线/禁用。其他 409 是业务冲突（如产品下仍有设备），消息直接展示原因 |
| 指令一直 TIMEOUT | 设备没订阅 `command/down` 或没回复；超时由 `command.timeout.seconds` 控制 |
| 队列满日志 `Batch queue 'x' is full` | 数据库/Influx 持续跟不上。加速写入或降负载；见 §2.3 |
| 压测后磁盘暴涨 | 通信记录（默认已关）或历史保留太长；检查 `IOT_LOG_OK_PROPERTY`、`IOT_INFLUX_RETENTION_DAYS`、`retention.*` |

### 4.4 部署与监控

| 现象 | 处理 |
|---|---|
| `docker compose up` 构建慢/失败 | 主机访问 Maven/npm/Docker Hub 不通：配镜像加速或代理，或本机 `-Prebuilt` |
| 前端打不开 | 防火墙没放行 `WEB_PORT`；`docker compose ps` 看 frontend |
| 页面能开但登录失败 | `docker compose logs backend`；确认 `IOT_ADMIN_PASSWORD` ≥ 8 位 |
| Grafana 面板空白 | `iot_uplink_*` 要等第一条设备消息；换 EMQX 版本后指标名可能变，在 Prometheus 搜 `emqx_` 再改查询 |
| InfluxDB 没有写入点数指标 | 1.8 的 `/metrics` 只有 Go 进程指标；用 Grafana 的 InfluxDB 数据源查业务库 |
| 容器 CPU/内存看不到 | 未部署 cAdvisor；用 `docker stats`，或自行加 cAdvisor + Prometheus job |

## 5. 运行手册

### 5.1 重启 broker 前后

1. 确认当前 `iot_mqtt_gateway_connections` = expected（16）。
2. 尽量在低峰做；满载时按 §3.1，恢复期会丢消息。
3. 重建后观察连接数回到 16（最多约 2 分钟），以及 EMQX 的 `messages_dropped` 是否停止增长。
4. 若长时间回不到 16，看后端日志 `MQTT gateway connection ... lost` / `cannot connect`，确认 `platform` 账号和回调仍有效。

### 5.2 下游变慢时

1. 看 `iot_batch_queue_pending{queue}` 是哪一个涨。
2. `history` → Influx/PG；`latest` → PG；`message-log` → PG（通常已关）。
3. 临时降负载（`--rate`）或加大对应 writer（`IOT_BATCH_HISTORY_WORKERS`）。
4. 不要先加连接/窗口：那只会把更多消息推给已经变慢的下游。

### 5.3 磁盘增长

- `iot_device_message_log`：默认关；若打开，按 7 天保留，10k msg/s 约 13 GB/小时。
- `iot_device_property_history`：90 天；未分区，大表清理会慢。
- InfluxDB：按保留策略；确认 `IOT_INFLUX_RETENTION_DAYS` 生效。
- Kafka：每分区 256 MB / 6 小时。

## 6. 安全

- 生产必须设置 `IOT_JWT_SECRET`、数据库/Influx/MQTT 密码、`IOT_METRICS_TOKEN`；`.env` 权限 600。
- `IOT_API_DOCS_ENABLED=false`（默认）；需要时临时打开。
- `/actuator/prometheus` 只给 Prometheus；不要暴露到公网。
- 9100（node-exporter）只允许可信机器。
- 没有 TLS：MQTT 和 HTTP 都是明文，公网部署必须加 TLS 终结。
- Grafana 默认管理员密码在 `.env`，生产请改掉并限制来源 IP。
