# 配置参考

这份文档是**环境变量的唯一权威表**。代码里的默认值在 `backend/core/src/main/resources/application.yml`，部署栈的默认值在 `docker-compose.yml` 和 `.env.example`；两处不一致的地方单独列在 §4。

优先级（从高到低）：

1. `docker compose` 的 `.env` / `environment:`
2. 进程环境变量（`mvn spring-boot:run` 前 export / `$env:`）
3. `application-<profile>.yml`
4. `application.yml` 里的 `${VAR:default}` 默认值

改完后端相关变量需要重启后端（compose 下用 `docker compose up -d backend`，`restart` 不会重新读取 `.env`）；改 EMQX 变量需要重建 emqx 容器。

## 1. 必须设置（没有可用默认值）

| 变量 | 用在哪 | 说明 |
|---|---|---|
| `IOT_JWT_SECRET` | 后端 | ≥ 32 字符；`local` profile 之外不设置会启动失败 |
| `IOT_ADMIN_PASSWORD` | 后端 | 首个管理员密码，≥ 8 位；**只在用户表为空时**创建 |
| `IOT_MQTT_PASSWORD` | 后端 | 平台账号 `platform` 连 broker 的密码，由后端自己校验，不需要在 EMQX 建用户 |
| `IOT_MQTT_HTTP_AUTH_TOKEN` | 后端 + broker | EMQX 回调 `/api/mqtt/auth`、`/api/mqtt/acl` 时带的 `X-Broker-Token` |
| `POSTGRES_PASSWORD` | compose | PostgreSQL 密码 |
| `INFLUX_PASSWORD` | compose | InfluxDB 管理员密码（`INFLUX_USER`，默认 `influx`） |
| `EMQX_DASHBOARD_PASSWORD` | compose | EMQX Dashboard 的 `admin` 密码 |
| `GRAFANA_ADMIN_PASSWORD` | compose | Grafana 的 `admin` 密码 |
| `IOT_METRICS_TOKEN` | 后端 + Prometheus | `/actuator/prometheus` 的 Bearer 令牌；为空时端点关闭 |

`deploy/vm-init-env.sh` 会一次性生成这些随机值（管理员密码由调用方通过 `OMNIHUB_ADMIN_PASSWORD` 传入），写进主机的 `.env`（权限 600）。

## 2. 部署栈（`.env`，`docker-compose.yml` 读取）

### 2.1 端口与镜像

| 变量 | 默认 | 说明 |
|---|---|---|
| `WEB_PORT` | `8088` | 前端 nginx 对外端口 |
| `MQTT_PORT` | `1883` | 设备接入端口 |
| `EMQX_DASHBOARD_PORT` | `18083` | EMQX Dashboard |
| `GRAFANA_PORT` | `3000` | Grafana |
| `POSTGRES_IMAGE` | `postgres:16` | |
| `EMQX_IMAGE` | `emqx/emqx:5.8.0` | 实际节点版本以 `emqx ctl status` 为准（当前部署机是 5.7.2） |
| `KAFKA_IMAGE` | `apache/kafka:3.9.0` | 单节点 KRaft |
| `PROMETHEUS_IMAGE` | `prom/prometheus:v2.53.0` | |
| `COMPOSE_FILE` | 空 | `-Prebuilt` 部署时由脚本写成 `docker-compose.yml:deploy/docker-compose.prebuilt.yml` |
| `BACKEND_JAVA_OPTS` | `-XX:MaxRAMPercentage=70` | 后端 JVM 参数 |

### 2.2 PostgreSQL

| 变量 | 默认 | 说明 |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `iot` / `iot` / 必填 | |
| `PG_SHARED_BUFFERS` | `2GB` | 镜像默认 128 MB，压测时太小 |
| `PG_EFFECTIVE_CACHE_SIZE` | `6GB` | |
| `PG_MAX_WAL_SIZE` | `8GB` | 避免检查点过频 |
| `PG_MAX_CONNECTIONS` | `200` | `IOT_DB_POOL + IOT_INGEST_DB_POOL` 必须留有余量 |
| `PG_SYNCHRONOUS_COMMIT` | `on` | **实例级**；ingest 连接池另有自己的 `off`，见 §3.3。实例级改 `off` 会让所有写入都不等 WAL，崩溃时丢最近几百毫秒 |
| `IOT_DB_POOL` | `40` | compose 传给后端；代码默认是 `20` |

### 2.3 EMQX

| 变量 | 默认 | 映射到的 EMQX 配置 | 说明 |
|---|---|---|---|
| `EMQX_MAX_INFLIGHT` | `4096` | `mqtt.max_inflight` | **真正决定上行接收窗口**：每个订阅会话最多多少条未确认消息。往上调这个才有用 |
| `EMQX_MAX_MQUEUE_LEN` | `100000` | `mqtt.max_mqueue_len` | 窗口满后的待投递队列；EMQX 默认 1000 |
| `EMQX_MAX_MAILBOX_SIZE` | `100000` | `force_shutdown.max_mailbox_size` | 超过就断开连接；默认 1000 |
| `EMQX_MAX_HEAP_SIZE` | `512MB` | `force_shutdown.max_heap_size` | 默认 32MB |
| `EMQX_TCP_BACKLOG` | `4096` | `listeners.tcp.default.tcp_options.backlog` | 不要超过主机 `net.core.somaxconn` |
| `EMQX_SHARED_SUBSCRIPTION_STRATEGY` | `hash_clientid` | `mqtt.shared_subscription_strategy` | 共享订阅分发策略，见 [ingest.md](ingest.md) §2 |
| `EMQX_API_KEY` / `EMQX_API_SECRET` | 空 | — | 仅当 EMQX 指标端点需要认证时，由 `deploy/emqx-bootstrap.sh` 填写 |

compose 里还写死了三个安全相关的值，**不读 `.env`**，改它们要直接改 `docker-compose.yml`：

| 写死项 | 值 | 说明 |
|---|---|---|
| `EMQX_ALLOW_ANONYMOUS` | `false` | 匿名设备一律拒绝，必须走 HTTP 认证回调 |
| `EMQX_AUTHORIZATION__NO_MATCH` | `deny` | ACL 无匹配时拒绝（默认是 allow） |
| `EMQX_DASHBOARD__DEFAULT_PASSWORD` | `${EMQX_DASHBOARD_PASSWORD}` | Dashboard 的 `admin` 密码只来自 `.env` |

> **EMQX 5.7+ 的坑**：共享订阅配置在 `mqtt.*`，不在 `broker.*`。compose 里必须用
> `EMQX_MQTT__SHARED_SUBSCRIPTION_STRATEGY`。写旧的 `EMQX_BROKER__...` 会被**静默忽略**，
> 实际保持 `round_robin`。验证：
> `docker compose exec emqx emqx ctl conf show mqtt | grep shared_subscription_strategy`。

### 2.4 InfluxDB 与历史存储

| 变量 | 默认（compose） | 说明 |
|---|---|---|
| `IOT_HISTORY_STORE` | `influxdb` | `influxdb` 或 `postgres`；**代码默认是 `postgres`** |
| `INFLUX_DB` | `iot` | 库名 |
| `INFLUX_USER` | `influx` | 管理员用户名 |
| `INFLUX_PASSWORD` | 必填 | |
| `IOT_INFLUX_RETENTION_DAYS` | `90` | 启动时创建/更新默认保留策略；`0` = 不创建（代码默认） |

### 2.5 Kafka（`IOT_INGEST_MODE=kafka` 时）

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_INGEST_MODE` | `direct` | `direct` 或 `kafka`，重启后端生效 |
| `IOT_KAFKA_TOPIC` | `iot-uplink` | |
| `IOT_KAFKA_GROUP` | `iot-platform` | 消费组 |
| `IOT_KAFKA_CONCURRENCY` | `8` | 消费线程数；超过分区数没有意义 |
| `IOT_KAFKA_PARTITIONS` | `8` | 自动建 topic 时的分区数 |
| `IOT_KAFKA_ACKS` | `1` | `0` / `1` / `all` |
| `KAFKA_RETENTION_BYTES` | `268435456` | 每分区 256 MB，防止压测写满磁盘 |
| `KAFKA_RETENTION_HOURS` | `6` | |
| `KAFKA_HEAP_OPTS` | `-Xms512m -Xmx1g` | |

### 2.6 监控

| 变量 | 默认 | 说明 |
|---|---|---|
| `PROMETHEUS_RETENTION` | `15d` | |
| `GRAFANA_ADMIN_PASSWORD` | 必填 | 用户 `admin` |
| `IOT_METRICS_TOKEN` | 必填 | 后端与 Prometheus 共用的 Bearer 令牌 |

## 3. 后端（`application.yml`）

### 3.1 HTTP / 安全

| 变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | compose 里固定 8080（nginx、健康检查、EMQX 回调都用它） |
| `IOT_TOMCAT_THREADS` | `400` | EMQX 认证回调也走这里 |
| `IOT_TOMCAT_ACCEPT_COUNT` | `500` | |
| `IOT_TOMCAT_MAX_KEEPALIVE_REQUESTS` | `-1` | `-1` = 不按请求数关闭长连接（Tomcat 出厂值是 100）。100 会切断 EMQX 的流水线回调，表现为设备被拒 `NOT_AUTHORIZED` |
| `IOT_TOMCAT_KEEPALIVE_TIMEOUT` | `120s` | |
| `IOT_JWT_SECRET` | 空 | 必填（`local` 除外），≥ 32 字符 |
| `IOT_JWT_TTL_MINUTES` | `120` | |
| `IOT_ADMIN_USERNAME` | `admin` | |
| `IOT_ADMIN_PASSWORD` | 空 | 见 §1 |
| `IOT_CORS_ORIGINS` | 空 | 逗号分隔；同源部署不用设 |
| `IOT_API_DOCS_ENABLED` | `false` | 打开 `/swagger-ui.html` 与 `/v3/api-docs` |
| `IOT_METRICS_TOKEN` | 空 | 见 §1 |

### 3.2 数据库与调度

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_DB_URL` | `jdbc:postgresql://localhost:5432/iot?stringtype=unspecified` | `stringtype=unspecified` 是 JSONB 写入需要的 |
| `IOT_DB_USER` / `IOT_DB_PASSWORD` | `iot` / 空 | |
| `IOT_DB_POOL` | `20` | Hikari 主池（HTTP/API） |
| `IOT_SCHEDULER_THREADS` | `4` | 每个 `@Scheduled` 任务一条；太小会让离线扫描被清理任务拖住 |

### 3.3 上行接入与写入

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_MQTT_BROKER_URL` | `tcp://localhost:1883` | compose 里是 `tcp://emqx:1883` |
| `IOT_MQTT_USERNAME` | `platform` | 平台账号，不受 Topic ACL 限制 |
| `IOT_MQTT_PASSWORD` | 空 | 必填 |
| `IOT_MQTT_CLIENT_ID` | `iot-platform-${random.uuid}` | 每次启动随机；配合 `clean-session=true` 避免残留会话继续排队 |
| `IOT_MQTT_HTTP_AUTH_TOKEN` | 空 | 必填 |
| `IOT_MQTT_SUBSCRIBE_QOS` | `1` | `0` 更快但可能丢 |
| `IOT_MQTT_INGEST_CONNECTIONS` | `16` | 共享订阅连接数；`1` = 单连接（broker 不支持 `$share` 时） |
| `IOT_MQTT_MAX_INFLIGHT` | `4096` | Paho 自己发布消息的在途上限（下行指令）；**不限制 broker 向平台投递**，对上行吞吐没有作用 |
| `IOT_MQTT_SHARED_GROUP` | `ingest` | 共享订阅组名 |
| `IOT_MQTT_CLEAN_SESSION` | `true` | 断开即删会话；订阅在每次连接后重建 |
| `IOT_INGEST_MODE` | `direct` | `direct` / `kafka` |
| `IOT_INGEST_LANES` | `0` | `0` = `max(8, 2 × CPU)` 条单线程 lane |
| `IOT_INGEST_LANE_QUEUE` | `20000` | 每条 lane 的队列长度 |
| `IOT_LANE_OVERFLOW` | `block` | `block` 背压（不丢）；`drop` 丢弃并计入 `iot_ingest_lane_dropped_total` |
| `IOT_LATEST_MIN_INTERVAL_MS` | `1000` | 同一设备同一属性的最新值最小写入间隔；`0` = 每条都写 |
| `IOT_INGEST_DB_POOL` | `12` | ingest 专用连接池；`0` = 共用 `IOT_DB_POOL` |
| `IOT_INGEST_DB_TIMEOUT_MS` | `2000` | 等连接的上限，超时整批丢弃并计数 |
| `IOT_INGEST_SYNCHRONOUS_COMMIT` | `off` | 仅 ingest 池；`on` 恢复每批等 WAL |
| `IOT_BATCH_QUEUE` | `200000` | 每个批队列的容量 |
| `IOT_BATCH_MAX` | `2000` | latest / message-log 每批上限 |
| `IOT_BATCH_HISTORY_MAX` | `5000` | 历史每批上限（也是 Influx 一次 HTTP 的点数） |
| `IOT_BATCH_LINGER_MS` | `50` | 第一批到达后再攒这么久才写；`0` = 立即写 |
| `IOT_BATCH_WORKERS` | `4` | latest / 通用写线程数（按设备 id 分散） |
| `IOT_BATCH_HISTORY_WORKERS` | `4` | 历史写线程数；15k × 7 属性需要 4，小部署可降到 `2` |
| `IOT_BATCH_LOG_WORKERS` | `2` | 通信记录写线程数 |
| `IOT_LOG_OK_PROPERTY` | `false` | 成功的属性上报是否写 `iot_device_message_log`。**默认关**：这张表上限约 4.2k 行/s，10k msg/s 时约 13 GB/小时 |

### 3.4 Kafka

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_KAFKA_BOOTSTRAP` | `localhost:9092` | compose 里 `kafka:9092` |
| `IOT_KAFKA_TOPIC` | `iot-uplink` | |
| `IOT_KAFKA_GROUP` | `iot-platform` | |
| `IOT_KAFKA_CONCURRENCY` | `8` | |
| `IOT_KAFKA_CREATE_TOPIC` | `true` | 无权限自动建 topic 时设 `false` 并手动建 |
| `IOT_KAFKA_PARTITIONS` | `8` | |
| `IOT_KAFKA_REPLICAS` | `1` | |
| `IOT_KAFKA_ACKS` | `1` | |

生产者/消费者的其余参数固定写在 `application.yml` 的 `spring.kafka`：压缩 `lz4`、`linger.ms=5`、`max.block.ms=3000`、`max-poll-records=500`、`auto-offset-reset=latest`。

### 3.5 历史存储

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_HISTORY_STORE` | `postgres` | `postgres` / `influxdb` |
| `IOT_INFLUX_URL` | `http://localhost:8086` | compose 里 `http://influxdb:8086` |
| `IOT_INFLUX_DB` | `iot` | |
| `IOT_INFLUX_USER` / `IOT_INFLUX_PASSWORD` | 空 | |
| `IOT_INFLUX_RP` | 空 | 空 = 用默认保留策略 |
| `IOT_INFLUX_CREATE_DB` | `false` | 启动时执行 `CREATE DATABASE` / 保留策略，需要管理权限 |
| `IOT_INFLUX_RETENTION_DAYS` | `0` | `0` = 不创建保留策略 |

### 3.6 设备、告警、WebSocket、看板

| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_AUTH_CACHE_WARMUP` | `true` | 启动时把全部设备装入内存（Web 服务开始接收前） |
| `IOT_AUTH_CACHE_REFRESH_SECONDS` | `300` | 定时全量刷新 |
| `IOT_AUTH_CACHE_MAX` | `200000` | |
| `IOT_ALARM_RULE_REFRESH_SECONDS` | `30` | 规则内存索引刷新 |
| `IOT_ALARM_QUEUE_CAPACITY` | `50000` | 告警评估队列；满了丢弃评估并计数，不阻塞 ingest |
| `IOT_ALARM_FLUSH_MS` | `1000` | `trigger_count` / `last_triggered_at` 合并落库周期 |
| `IOT_WS_SENDER_THREADS` | `2` | |
| `IOT_WS_QUEUE_CAPACITY` | `20000` | |
| `IOT_WS_COALESCE_MILLIS` | `200` | 同设备属性推送合并窗口；`0` = 每条都推 |
| `IOT_DASHBOARD_CACHE_SECONDS` | `10` | 看板聚合缓存；`0` = 每次查库 |
| `IOT_DASHBOARD_ZONE` | `UTC` | "今日"按哪个时区算；`.env.example` 写 `Asia/Shanghai`，`localext`/`local` 也是 |

## 4. 代码默认值 vs 部署栈默认值

这些差异是有意的，但容易误读，单独列出来：

| 变量 | `application.yml`（裸跑 jar） | `docker-compose.yml`（整套栈） | 为什么 |
|---|---|---|---|
| `IOT_DB_POOL` | `20` | `40` | 栈里有独立 PG 和更多并发 |
| `IOT_HISTORY_STORE` | `postgres` | `influxdb` | 栈里带了 InfluxDB，历史不占 PG |
| `IOT_INFLUX_CREATE_DB` | `false` | `true` | 栈里的 Influx 由后端初始化 |
| `IOT_INFLUX_RETENTION_DAYS` | `0` | `90` | 栈里默认 90 天 |
| `IOT_INFLUX_URL` | `http://localhost:8086` | `http://influxdb:8086` | 容器网络 |
| `IOT_KAFKA_BOOTSTRAP` | `localhost:9092` | `kafka:9092` | 容器网络 |
| `IOT_DASHBOARD_ZONE` | `UTC` | `.env`（示例 `Asia/Shanghai`） | 看板"今日"口径 |
| `SERVER_PORT` | `8080` | 固定 `8080` | 容器内必须固定 |

其余吞吐相关默认值（16 连接 / broker 4096 在途 / 10 万 mqueue / 日志关 / `synchronous_commit=off`）在两层是一致的。

## 5. 两套常用配置

**满载单机（当前默认，15k msg/s）**

```dotenv
IOT_MQTT_INGEST_CONNECTIONS=16
EMQX_MAX_INFLIGHT=4096          # 上行接收窗口由 broker 这一侧决定
# Paho 的发送窗口只影响下行指令，保持 4096 即可
IOT_MQTT_MAX_INFLIGHT=4096
IOT_LOG_OK_PROPERTY=false
# 默认就是 4，这里写出来是为了和下面的小部署对照
IOT_BATCH_HISTORY_WORKERS=4
```

**小部署（几 k msg/s，少占内存）**

```dotenv
IOT_MQTT_INGEST_CONNECTIONS=4
EMQX_MAX_INFLIGHT=1024
IOT_MQTT_MAX_INFLIGHT=1024
IOT_LOG_OK_PROPERTY=false
IOT_BATCH_HISTORY_WORKERS=2
```

窗口的算法、为什么只需要调 broker 侧、以及每一档的实测值，见 [ingest.md](ingest.md) §3。
