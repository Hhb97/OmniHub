# 监控：Prometheus + Grafana

> 状态：**后端指标**有集成测试验证（`MetricsEndpointIntegrationTest`：带令牌可读、无令牌 401、指标名齐全）。已在一台 Docker 虚拟机上部署：Prometheus 的 6 个采集目标全部 UP，三个看板自动加载；我把看板里所有指标名与 Prometheus 里实际存在的指标逐一对照，修正了一个（EMQX 5.7 的认证失败指标是 `emqx_authentication_failure`）。**没有逐个面板核对过显示效果**；`iot_uplink_*` 等计数器要等第一条设备消息到来才会出现，没有设备上报时相关面板为空是正常的。**没有设备、没有压测数据时，吞吐、积压类面板的表现没有验证过。**

## 1. 组成
```
后端 /actuator/prometheus ──┐
EMQX /api/v5/prometheus/stats ─┤
InfluxDB /metrics ─────────────┼──► Prometheus ──► Grafana（3 个看板）
postgres-exporter ─────────────┤                    └─ 另有 InfluxDB 数据源（查业务数据）
kafka-exporter（偏移量/积压）──┤
node-exporter（主机）──────────┘
```
| 服务 | 作用 | 端口 |
|---|---|---|
| prometheus | 每 15 秒采集，保留 15 天（`PROMETHEUS_RETENTION`） | 不对外 |
| grafana | 看板，自动加载数据源和看板 | `GRAFANA_PORT`，默认 **3000** |
| postgres-exporter | PostgreSQL 指标 | 不对外 |
| node-exporter | 主机 CPU、内存、磁盘、网络、TCP 连接 | **9100，主机网络**，请用防火墙只允许可信机器访问 |

登录 Grafana：`http://<主机IP>:3000`，用户 `admin`，密码是主机 `.env` 里的 `GRAFANA_ADMIN_PASSWORD`。首页是“吞吐与消息积压”。

## 2. 三个看板（文件夹 OmniHub）
**① OmniHub · 吞吐与消息积压**（最重要）
- 顶部 6 个数：当前吞吐、平均/P95 排队延迟、**消息积压总量**、丢弃速率、后端 MQTT 连接状态。
- 上行吞吐：按类型（属性/事件/状态/应答）、被拒绝的消息（按原因）、延迟分位数、MQTT 处理线程队列。
- 批量写入：三个队列（latest/history/message-log）的积压、使用率、丢弃、写入速率、平均每批条数、单批写入 P95 耗时。
- EMQX 回调：认证/授权的 QPS、P95 延迟、失败（401/5xx）。**连接风暴时先看这里。**
- Kafka（仅 `kafka` 模式有数据）：消费积压、写入/消费速率、broker 数、各分区积压、转发失败丢弃。“消息积压总量”也包含 Kafka 的消费积压。

**② OmniHub · 中间件**：七个采集目标的 UP/DOWN；Kafka（broker 数、分区数、消费组成员、topic 内保留的消息数、各分区写入速率和积压）；EMQX（连接数、订阅数、收发速率、连接/断开速率、各类丢弃、认证失败）；PostgreSQL（连接、事务、行写入、缓存命中率、库大小、HikariCP 连接池）；InfluxDB（进程 CPU、内存、goroutines、文件句柄，以及后端写 Influx 的批量耗时）。

**③ OmniHub · 系统与 JVM**：主机 CPU/内存/负载/磁盘/网络/TCP 连接与 TIME_WAIT/文件句柄；后端堆内存、GC、线程、Tomcat 忙线程、HTTP 请求速率与延迟。

**④ OmniHub · 业务概览**：在线 / 离线 / 未激活 / 已禁用设备数、设备总数、**在线率**、设备数量变化、上下线事件速率（看连接抖动）、数据库与后端内存里的在线数对比、各产品的在线设备数和设备总数、产品数、认证缓存条数、页面实时连接数、活跃告警（按级别）、未确认告警、24 小时内触发的告警、启用的告警规则、在途下行指令、上行消息速率。

## 3. “消息积压”到底看哪里
消息从 EMQX 到落库要经过几处排队，积压可能出现在任何一处，看板分别列出：

| 位置 | 指标 | 说明 |
|---|---|---|
| EMQX → 后端 | EMQX 看板里的“丢弃消息 / 投递队列满” | 平台只用一个 MQTT 连接订阅全部上行，后端消费不过来时 EMQX 会丢弃，**这种积压是看不到队列长度的，只能看丢弃数** |
| 后端处理线程 | `iot_lane_queue_pending`、`iot_lane_queue_max` | direct 模式；最满的那条线程接近 20000 就会拖慢接收 |
| Kafka | `kafka_consumergroup_lag{topic="iot-uplink"}`（kafka-exporter） | kafka 模式；消费组落后多少条，已实测可用。后端客户端指标 `kafka_consumer…records_lag_max` 作补充 |
| 批量写队列 | `iot_batch_queue_pending{queue}` | 等待写入 PG/Influx 的条数，最常见的积压位置 |
| 丢失 | `iot_batch_queue_dropped_total`、`iot_ingest_forward_dropped_total` | 队列满、写失败、转发 Kafka 失败；**应为 0** |

**判断思路**
1. “丢弃”大于 0 → 已经在丢数据，先降负载或加速下游。
2. 批量写队列积压持续上升、平均每批条数接近上限、单批写入耗时变长 → 数据库（PG/Influx）写不动，看“中间件”看板的 PG/Influx 面板和磁盘 IO。
3. 处理线程队列积压 → 后端 CPU 不够或某个环节慢，看“系统与 JVM”。
4. 回调 QPS 很高、延迟高、有 401/5xx → 连接风暴或回调有问题，参考 `docs/tuning.md` 第 5 节。
5. 排队延迟（P95）升高但队列不大 → kafka 模式下看 Kafka 往返和 lag。

## 4. 后端导出的指标（节选）
| 指标 | 含义 |
|---|---|
| `iot_ingest_processed_total` | 进入处理器的上行消息总数，`rate()` 即吞吐 |
| `iot_uplink_messages_total{type}` | 按类型：PROPERTY / EVENT / STATUS / REPLY |
| `iot_uplink_rejected_total{reason}` | unknown_device / disabled / oversize / bad_json / unsupported_topic |
| `iot_ingest_delay_ms_bucket/_sum/_count` | 从收到 MQTT 到开始处理的耗时（毫秒），不含入库 |
| `iot_batch_queue_pending / _capacity{queue}` | 批量写队列积压与容量 |
| `iot_batch_queue_written_total / _dropped_total{queue}` | 已写入 / 丢失 |
| `iot_batch_size_*{queue}` | 每批条数（`sum/count` 为平均） |
| `iot_batch_flush_seconds_bucket{queue}` | 一次批量写的耗时 |
| `iot_lane_queue_pending / _max` | MQTT 处理线程队列 |
| `iot_mqtt_gateway_connected` | 后端的 MQTT 连接是否正常 |
| `iot_devices{status}` | 设备数：ONLINE / OFFLINE / INACTIVE（未删除的，所有租户合计） |
| `iot_product_devices{product_key,product_name,status}` | 每个产品各状态的设备数（最多 600 条序列） |
| `iot_devices_disabled`、`iot_products{status}` | 被禁用的设备数、产品数 |
| `iot_alarms_active{level}`、`iot_alarms_unacknowledged`、`iot_alarms_triggered_24h`、`iot_alarm_rules_enabled` | 告警 |
| `iot_commands_in_flight{status}` | 在途下行指令：PENDING / SENT / RECEIVED |
| `iot_device_status_changes_total{to}` | 设备上线 / 下线事件数，`rate()` 即连接抖动 |
| `iot_devices_online_in_memory`、`iot_device_auth_cache_entries`、`iot_ws_sessions` | 后端内存里认为在线的设备数、认证缓存条数、页面实时连接数 |
| `iot_business_metrics_last_refresh_timestamp_seconds`、`iot_business_metrics_refresh_failures_total` | 业务指标的刷新时间和失败次数 |
| `http_server_requests_seconds_*{uri,status}` | HTTP 请求（含 `/api/mqtt/auth`、`/api/mqtt/acl`） |
| `jvm_*`、`tomcat_*`、`hikaricp_*`、`process_*` | Spring Boot 自带 |

业务指标（`iot_devices` 等）由后端每 15 秒（`iot.metrics.business-refresh-seconds`）在后台查一次数据库后更新内存里的数值，Prometheus 抓取时不会查库，所以数值最多滞后 15 秒；范围是整套系统（所有租户）。

## 5. 安全
- `/actuator/prometheus` 需要请求头 `Authorization: Bearer <IOT_METRICS_TOKEN>`；令牌为空时端点关闭；登录用的 JWT 不能读取它。令牌只在 `.env`，由 compose 传给后端和 Prometheus。
- Prometheus 不对外开放端口。需要临时查询：`docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up'`。
- 看板里的 Grafana 管理员密码在 `.env`；生产请改成自己的并限制来源 IP。

## 6. 需要你核对的地方
- **EMQX 指标**：在 EMQX 5.7.2 上，`/api/v5/prometheus/stats` 无需认证就能访问，指标名已对照过。换 EMQX 版本后某个面板没有数据时，在 Prometheus 里搜 `emqx_` 看真实名字，再在 Grafana 里改查询。`deploy/emqx-bootstrap.sh` 会检测该端点是否需要认证，需要时创建 API key 写入 `.env` 并重启 Prometheus。
- **InfluxDB 1.8 的 `/metrics`**：只有 Go 进程指标（CPU、内存、goroutines），没有写入点数、序列数。要看业务数据可用 Grafana 的 “InfluxDB (iot)” 数据源，例如 `SELECT count("num") FROM "device_property" WHERE time > now() - 5m GROUP BY time(1m)`（写入点数趋势）。
- **Kafka lag** 指标名由 Micrometer 从 Kafka 客户端导出，面板用正则匹配 `kafka_consumer.*records_lag_max`。
- **容器级指标**（每个容器的 CPU/内存）没有部署 cAdvisor（镜像在部分网络环境拉取困难）；需要时用 `docker stats`，或自行加 cAdvisor 再加一个 Prometheus job。
- **node-exporter** 使用主机网络，通过 `host.docker.internal`（compose 的 `host-gateway`）被 Prometheus 访问，要求 Docker 20.10 以上。
- 没有配置告警规则；常用的告警条件见 §3 的“丢弃 > 0”“积压持续上升”“目标 DOWN”。

## 7. 本地开发（不用 Docker）时
给后端设置环境变量 `IOT_METRICS_TOKEN=任意字符串`，用同样的 Bearer 令牌访问 `http://localhost:<端口>/actuator/prometheus` 即可看到指标；如需看板，可自行起 Prometheus 和 Grafana，并导入 `deploy/monitoring/grafana/dashboards/*.json`（数据源 uid 为 `prometheus`）。

## 8. 文件位置
| 内容 | 路径 |
|---|---|
| 服务定义 | `docker-compose.yml`（prometheus、postgres-exporter、node-exporter、grafana） |
| 采集配置 | `deploy/monitoring/prometheus.yml` |
| Grafana 数据源 / 看板加载 | `deploy/monitoring/grafana/provisioning/` |
| 看板 JSON | `deploy/monitoring/grafana/dashboards/*.json` |
| 后端指标代码 | `common/BatchQueue.java`、`integration/mqtt/IngestStats.java`、`integration/mqtt/MqttGateway.java`、`security/MetricsTokenFilter.java` |
