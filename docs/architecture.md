# 架构说明

```
 浏览器 (Vue 3) ──HTTP /api──┐
        └────WebSocket /ws──┤
                            ▼
                  ┌──────────────────────┐   JDBC    ┌────────────┐
 设备 ──MQTT──▶    │  Spring Boot 后端     │──────────▶│ PostgreSQL │
 (每台 1 连接)     │  MQTT 网关 (Paho)     │           └────────────┘
                  │  ├ 16 条共享订阅连接   │   HTTP    ┌────────────┐
                  │  ├ lane（按设备哈希）  │──────────▶│ InfluxDB   │
                  │  ├ 批量写队列          │           └────────────┘
                  │  └ Kafka（可选）       │   produce ┌────────────┐
                  └──────────────────────┘──────────▶│ Kafka      │
                            ▲  HTTP 认证/ACL 回调      └────────────┘
                            │
                       EMQX (broker)
```

职责边界：

- **Broker** 只负责连接与消息传输；设备身份与 Topic 权限由平台决定。EMQX 通过 HTTP 回调 `/api/mqtt/auth`、`/api/mqtt/acl`；内置开发 Broker 在进程内调用同一套逻辑。
- **后端**：REST + WebSocket + 自己作为 MQTT 客户端（账号 `platform`）订阅所有设备上行主题、发布下行指令。上行默认经共享订阅分摊到 16 条连接，再进进程内 lane 和批量写队列；也可以先写 Kafka 再消费。
- **前端**：只通过 HTTP/WebSocket 访问后端，权限最终由后端 `@PreAuthorize` 校验。
- **存储**：PostgreSQL 存业务数据、最新值、事件、通信记录；属性历史可存 PostgreSQL 或 InfluxDB（compose 默认 InfluxDB）；Kafka 只在 `IOT_INGEST_MODE=kafka` 时参与。

## 后端模块（`backend/`）

后端是一个多模块 Maven 工程：源码按职责拆到 `platform-*` 模块，**只有 `core` 产生可执行的 Spring Boot jar**（`core/target/iot-backend-*.jar`）。模块间的依赖在编译期就固定下来，越界会直接编译失败，不靠约定。

| 模块 | 包 | 内容 |
|---|---|---|
| `platform-common` | `common` `config` | 统一响应 `{code,message,data}`、分页、异常处理、脱敏、`BatchQueue`/`ShardedBatchQueue`、`IotProperties`、MyBatis-Plus 配置、`IngestDb` |
| `platform-messaging` | `messaging` | 传输无关的端口：`Topics`（主题约定）、`DownlinkPublisher`、`BrokerAdmin` |
| `platform-identity` | `identity.core` `identity.web` `system` | `core` 放 `LoginUser`/`CurrentUser`/`JwtService`，`web` 放过滤链与 `SecurityConfig`，`system` 放用户、角色、权限、日志 |
| `platform-product` | `product` `thingmodel` | 产品；物模型（版本化 JSONB）与值校验 |
| `platform-device` | `device` | 设备与凭证、在线状态、认证缓存、`DeviceRuntime` |
| `platform-telemetry` | `telemetry` | 属性/事件入库、最新值、历史、趋势聚合、保留清理、通信记录、Influx 写入 |
| `platform-command` | `command` | 指令状态机与超时 |
| `platform-alarm` | `alarm` | 规则、告警引擎、告警记录 |
| `platform-realtime` | `integration.ws` | WebSocket 会话与合并推送 |
| `platform-ingest` | `integration.mqtt` | MQTT 网关、共享订阅、lane、`UplinkProcessor`、Kafka 转发与消费、内置 Broker、broker 认证回调、接入统计 |
| `platform-dashboard` | `dashboard` | 统计接口与业务指标刷新 |
| `core` | `com.omnihub.iot` `config` | 启动类、`application*.yml`、Flyway 迁移、`OpenApiConfig`、`EmbeddedPostgresConfig`、跨模块集成测试；唯一打包成 boot jar 的模块 |

依赖方向（父 pom 的 `<modules>` 顺序就是构建顺序，下层不能反向依赖上层）：

```
core → dashboard → ingest → realtime → alarm → command → telemetry → device → product → identity → messaging → common
```

三条硬规则：

- **领域模块不认 Broker。** `device`、`command` 只依赖 `platform-messaging` 里的端口（`DownlinkPublisher`、`BrokerAdmin`），由 `platform-ingest` 实现；因此可以再加一个 AMQP/CoAP 接入模块而不动业务代码。
- **`identity.core` 只放“当前用户 + 令牌”这类被所有模块用到的类型**，安全过滤链在 `identity.web`。这样 `system` 能依赖 `identity.core`，而 `identity.web` 再依赖 `system`，两个包不再互相引用。
- **只有 `core` 有 `spring-boot-maven-plugin`**，其余模块是普通 jar；`spring-boot:run` 用 `mvn -pl core spring-boot:run`。

Controller 只做参数与权限；业务在 Service；DTO/VO 用 record。模块间通过 Spring 事件解耦：`PropertyReportedEvent`、`DeviceEventReported`、`DeviceStatusEvent`、`CommandChangedEvent`、`AlarmChangedEvent`。告警引擎与 WebSocket 推送都是监听者，失败不会影响入库。

## 关键流程

**上报**

1. 设备发布 `iot/{pk}/{dk}/property/post`（QoS1）。
2. EMQX 通过共享订阅 `$share/ingest/...` 把消息投给平台的**一条**连接（共 16 条，`hash_clientid` 保证同一设备固定到同一条）。
3. `MqttGateway` 按设备哈希放进一条单线程 lane，保证同设备有序。
4. `UplinkProcessor` 解析 Topic、校验设备/启用状态、16KB 限制、JSON 对象、按消息 `id` 去重。
5. `TelemetryService` 按物模型逐属性校验；非法/越界/未定义属性被拒绝，其余有效属性仍入库。
6. 写路径：
   - `latest` 队列 → `iot_device_latest`（每设备一行 JSONB，逐属性比较时间戳）；
   - `history` 队列 → `iot_device_property_history` 或 InfluxDB；
   - `message-log` 队列 → `iot_device_message_log`（默认不记成功的属性上报）；
   - `DeviceRuntime` 内存记录 + 每 3 秒批量刷 `last_comm_at`。
7. 发布 Spring 事件 → 告警评估 + WebSocket 推送。两者都不阻塞入库。

`IOT_INGEST_MODE=kafka` 时第 3–4 步换成：回调线程转发 Kafka（key = `productKey.deviceKey`）→ 消费者 → 同一个 `UplinkProcessor`。详细语义见 [ingest.md](ingest.md)。

**在线状态**（两层，互相独立）

- `mqtt_connected`：传输层。Broker 连接/断开事件（内置 Broker）、`status` 主题的 `online/offline`（含遗嘱）、收到任意上行消息。
- `online_status`（INACTIVE/ONLINE/OFFLINE）：业务层。仅当收到消息/连接事件时为 ONLINE；`DeviceRuntime.sweepOffline` 每 15 秒把超过 `device.offline.timeout.seconds`（默认 90）无上行的设备置为 OFFLINE——即使 MQTT 仍显示已连接。

**指令**：校验权限 → 设备必须启用且 ONLINE（否则 409）→ 按物模型校验参数 → 落库 `PENDING` → 发布 QoS1 → `SENT`；设备回复 `RECEIVED`/`SUCCESS`/`FAILED`；超过 `command.timeout.seconds`（默认 30）仍未终态则 `TIMEOUT`。所有状态迁移是带"来源状态"条件的 UPDATE，重复/迟到/乱序回复不会破坏状态；终态不可再改。

**告警**：`alarm_record` 上有 `(rule_id, device_id) WHERE status='ACTIVE'` 部分唯一索引，保证同一规则对同一设备同时只有一条活动告警；持续违规只累加次数与最后触发时间；恢复后再次违规才新建，规则可设静默期。EVENT 类告警无自动恢复条件，确认即关闭。评估在独立线程池上，队列满时丢弃评估而不是阻塞 ingest。

**安全**：BCrypt 存用户密码；设备密钥为 256 位随机值，库中只存加盐 SHA-256；登录失败 5 次/5 分钟锁定；JWT 默认 120 分钟，退出写入内存黑名单；每次请求重新读取用户权限，禁用/改角色即时生效；所有查询按 `tenant_id` 过滤；日志与审计中密码/密钥/令牌字段被脱敏；Swagger 默认关闭。

## 扩展点

- **时序库**：替换 `TelemetryRepository` / `TelemetryService` 的历史写入与查询；已有 PostgreSQL 与 InfluxDB 两种实现。
- **其他协议**：新增 `integration.*` 子包，复用 `TelemetryService` / `CommandService`。
- **多实例**：共享订阅已经能把上行分摊到多个实例，但去重缓存、告警计数、Kafka 消费组仍按单实例设计，需要先改这些。
- **历史保留**：`retention.*` 配置 + 每日 03:17 清理任务。

## 相关文档

- 上行细节与容量：[ingest.md](ingest.md)
- 表结构：[data-model.md](data-model.md)
- 部署与配置：[deployment.md](deployment.md)、[configuration.md](configuration.md)
- 监控与已知行为：[operations.md](operations.md)
- 历史复盘：[perf-ingest-retro.md](perf-ingest-retro.md)
