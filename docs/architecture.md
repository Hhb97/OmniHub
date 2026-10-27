# 架构说明

```
 浏览器 (Vue3) ──HTTP /api──┐
        └────WebSocket /ws──┤
                            ▼
                  ┌──────────────────┐   JDBC    ┌────────────┐
 设备 ──MQTT──▶ Broker ◀──▶│ Spring Boot 后端 │──────────▶│ PostgreSQL │
 (模拟器)   (EMQX/内置)     │  MQTT 网关(Paho)  │           └────────────┘
                  ▲  HTTP 认证/ACL 回调 │
                  └────────────────────┘
```

职责边界：
- **Broker** 只负责连接与消息传输；设备身份与 Topic 权限由平台决定（EMQX 通过 HTTP 回调 `/api/mqtt/auth`、`/api/mqtt/acl`；内置开发 Broker 在进程内调用同一套逻辑）。
- **后端**：REST + WebSocket + 自己作为 MQTT 客户端（账号 `platform`）订阅所有设备上行主题、发布下行指令。
- **前端**：仅通过 HTTP/WebSocket 访问后端，权限最终由后端 `@PreAuthorize` 校验。

## 后端分层（`com.omnihub.iot`）
| 包 | 内容 |
|---|---|
| `common` | 统一响应 `{code,message,data}`、分页、异常处理、敏感信息脱敏 |
| `config` / `security` | 配置属性、MyBatis-Plus、JWT、Spring Security |
| `system` | 用户、角色、权限、配置、操作/登录日志、管理员初始化 |
| `product` `thingmodel` `device` | 产品；物模型（版本化 JSONB）与值校验；设备与凭证、在线状态 |
| `telemetry` | 上行属性/事件入库、最新值、历史、趋势聚合、保留清理 |
| `command` | 指令状态机与超时 |
| `alarm` | 规则、告警引擎、告警记录 |
| `dashboard` | 统计接口 |
| `integration.mqtt` / `integration.ws` | MQTT 网关、上行处理、内置 Broker、WebSocket |

Controller 只做参数与权限；业务在 Service；DTO/VO 用 record。模块间通过 Spring 事件解耦：`PropertyReportedEvent`、`DeviceEventReported`、`DeviceStatusEvent`、`CommandChangedEvent`、`AlarmChangedEvent`，告警引擎与 WebSocket 推送均是监听者，其失败不会影响入库。

## 关键流程
**上报**：设备 → Broker → `MqttGateway`（按设备哈希分到 8 条单线程队列，保证同设备有序）→ `UplinkProcessor`（解析 Topic、校验设备/启用状态、16KB 限制、JSON 校验、按消息 `id` 去重）→ `TelemetryService`（按物模型校验每个属性）→ 写 `iot_device_property_history` 与 upsert `iot_device_latest`（旧时间戳不覆盖新值）→ 发布事件 → 告警评估 + WebSocket 推送。非法/越界/未定义的属性被拒绝并记入通信记录，其余有效属性仍入库。

**在线状态**（两层，互相独立）：
- `mqtt_connected`：传输层。Broker 连接/断开事件（内置 Broker）、`status` 主题的 `online/offline`（含遗嘱）、收到任意上行消息。
- `online_status`（INACTIVE/ONLINE/OFFLINE）：业务层。仅当收到消息/连接事件时为 ONLINE；`DeviceRuntime.sweepOffline` 每 15 秒把超过 `device.offline.timeout.seconds`（默认 90）无上行的设备置为 OFFLINE——即使 MQTT 仍显示已连接。

**指令**：校验权限 → 设备必须启用且 ONLINE（否则 409）→ 按物模型校验参数 → 落库 `PENDING` → 发布 QoS1 → `SENT`；设备回复 `RECEIVED`/`SUCCESS`/`FAILED`；超过 `command.timeout.seconds`（默认 30）仍未终态则 `TIMEOUT`。所有状态迁移是带“来源状态”条件的 UPDATE，重复/迟到/乱序回复不会破坏状态；终态不可再改。

**告警**：`alarm_record` 上有 `(rule_id, device_id) WHERE status='ACTIVE'` 唯一索引，保证同一规则对同一设备同时只有一条活动告警；持续违规只累加次数与最后触发时间；恢复后再次违规才新建，规则可设静默期。EVENT 类告警无自动恢复条件，确认即关闭。

**安全**：BCrypt 存用户密码；设备密钥为 256 位随机值，库中只存加盐 SHA-256；登录失败 5 次/5 分钟锁定；JWT 默认 120 分钟，退出写入内存黑名单；每次请求重新读取用户权限，禁用/改角色即时生效；所有查询按 `tenant_id` 过滤；日志与审计中密码/密钥/令牌字段被脱敏；Swagger 默认关闭。

## 扩展点
- 时序库：替换 `TelemetryService` 中历史写入/查询。
- 历史保留：`retention.*` 配置 + 每日 03:17 清理任务。
- 其他协议：新增 `integration.*` 子包，复用 `TelemetryService`/`CommandService`。
