# 数据模型

PostgreSQL 14+（开发与部署用 16）。结构由 Flyway 管理，迁移在 `backend/core/src/main/resources/db/migration/`：

- `V1__init_schema.sql`：全部表结构
- `V2__seed_permissions.sql`：租户、权限目录、内置角色、系统配置
- `V3__latest_one_row_per_device.sql`：把最新值从"每属性一行"改成"每设备一行 JSONB"

管理员用户不在迁移里，由启动时的 `AdminInitializer` 按 `IOT_ADMIN_PASSWORD` 创建。

## 1. 约定

- 时间一律 `timestamptz`（UTC）。
- 业务表用 `deleted smallint` 逻辑删除；唯一约束是带 `WHERE deleted = 0` 的部分唯一索引，删除后标识可复用。
- JSON 用 `jsonb`；JDBC 连接串必须带 `stringtype=unspecified`，才能把字符串直接写进 jsonb 列。
- 设备数据表（latest / history / event / message log）**没有外键、只追加或按主键 upsert**，高频路径不做关联查询。
- 历史与事件不做分区；大数据量时应改为按月分区或换时序库（见 §6）。

## 2. 业务/配置表

| 表 | 说明 |
|---|---|
| `sys_tenant` | 租户（目前只有 default） |
| `sys_user` | 用户；`username` 部分唯一；`tenant_id` 索引 |
| `sys_role` | 角色；`(tenant_id, code)` 部分唯一 |
| `sys_permission` | 权限码（MENU / API） |
| `sys_user_role` / `sys_role_permission` | 关联表 |
| `sys_config` | 键值配置：离线超时、指令超时、保留天数 |
| `sys_operation_log` / `sys_login_log` | 审计日志，`created_at DESC` 索引 |
| `iot_product` | 产品；`product_key` 部分唯一；`thing_model_version` 当前版本 |
| `iot_thing_model` | 物模型版本；`(product_id, version)` 唯一；`content` jsonb = `{properties,events,services}`，与设备数据分离 |
| `iot_device` | 设备；`(product_id, device_key)` 唯一；`secret_hash/secret_salt`（不存明文）；`mqtt_connected`、`online_status`、`last_online_at/last_offline_at/last_comm_at`；`(tenant_id, online_status)` 索引 |
| `iot_device_command` | 指令；`command_no` 唯一；状态与各时间点；`timeout_at`；未终态行的部分索引供超时扫描 |
| `alarm_rule` | 告警规则（范围/类型/阈值/连续次数/静默/级别） |
| `alarm_record` | 告警记录；部分唯一索引 `(rule_id, device_id) WHERE status='ACTIVE'` 防重复 |
| `alarm_action` | 告警处理记录（TRIGGER / ACK / COMMENT / RECOVER） |

`iot_device` 的两个状态是独立的：

- `mqtt_connected`：传输层，broker 连接/断开事件或 `status` 主题；
- `online_status`（INACTIVE / ONLINE / OFFLINE）：业务层，超时没有上行就置 OFFLINE，即使 MQTT 还连着。

## 3. 设备数据表

| 表 | 写入方式 | 说明 | 索引 |
|---|---|---|---|
| `iot_device_latest` | 主键 upsert，按设备批内合并 | **一台设备一行**，`values JSONB` 存全部属性最新值 | `device_id` 主键 |
| `iot_device_property_history` | 批量 `INSERT`，不去重 | 每个样本一行；`num_value` 是数值投影（布尔 0/1）供聚合 | `(device_id, identifier, ts DESC)`、`(ts)` |
| `iot_device_event` | 逐条/批量 `INSERT` | 设备事件；`params` jsonb | `(device_id, ts DESC)` |
| `iot_device_message_log` | 批量 `INSERT` | 通信记录；payload 脱敏并截断到 2 000 字符 | `(device_id, created_at DESC)`、`(created_at)` |

### 3.1 最新值：一台设备一行（V3）

```sql
CREATE TABLE iot_device_latest (
    device_id BIGINT PRIMARY KEY,
    values    JSONB  NOT NULL
);
```

`values` 的形状：

```json
{
  "temperature": {"v": 21.5, "t": 1793179493731},
  "switch":      {"v": true, "t": 1793179493731}
}
```

- `v` 是原值，`t` 是该属性的上报时间（毫秒）。
- 一条带 7 个属性的消息只产生 **1 行 UPDATE**；旧结构 `(device_id, identifier)` 会产生 7 行。
- upsert 逐个属性比较 `t`：只有更新的上报时间才覆盖，迟到的旧值不会把新值改回去。
- 接口 `/devices/{id}/latest` 的返回结构不变，`reportedAt` 来自 `t`。
- 写入有采样：同一设备同一属性最多每 `IOT_LATEST_MIN_INTERVAL_MS`（默认 1 000 ms）写一次，尾部补偿保证最后一条落库。它只是"当前快照"，审计数据看历史表。

迁移 `V3` 会用 `jsonb_object_agg` 把旧数据聚合过去，然后重命名新表。

### 3.2 历史

```sql
CREATE TABLE iot_device_property_history (
    id         BIGSERIAL PRIMARY KEY,
    device_id  BIGINT           NOT NULL,
    identifier VARCHAR(64)      NOT NULL,
    num_value  DOUBLE PRECISION,          -- 数值/布尔(0/1)，非数值为空
    value      JSONB            NOT NULL,
    ts         TIMESTAMPTZ      NOT NULL
);
```

- 每个样本一行，**不去重**；批量写只减少语句数和往返，不减少行数。
- 趋势查询用 `num_value` 做 `avg`；非数值属性没有趋势曲线。
- `IOT_HISTORY_STORE=influxdb` 时这张表不写，历史进 InfluxDB（见 §4）。

### 3.3 通信记录

```sql
CREATE TABLE iot_device_message_log (
    id         BIGSERIAL PRIMARY KEY,
    device_id  BIGINT       NOT NULL,
    direction  VARCHAR(4)   NOT NULL,     -- UP | DOWN
    type       VARCHAR(16)  NOT NULL,     -- PROPERTY | EVENT | STATUS | COMMAND | REPLY
    topic      VARCHAR(255),
    payload    VARCHAR(2000),
    result     VARCHAR(16)  NOT NULL,     -- OK | INVALID | REJECTED
    message    VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL
);
```

- `created_at` 是**入队时刻**，不是写库时刻。
- 默认 `IOT_LOG_OK_PROPERTY=false`：只记失败、事件、状态、指令、下行；成功的属性上报不记。
- 这张表的上限约 4.2k 行/s，10k msg/s 时约 13 GB/小时，是全链路最大的写放大来源。仪表盘的"上报趋势 / 今日消息数 / 活跃设备"统计它，关闭后这三项会偏低。

## 4. InfluxDB 1.8（可选历史存储）

```
measurement: device_property
tags:        device_id, identifier
fields:      num  (float, 仅数值/布尔)
             json (原始值的 JSON 文本)
timestamp:   毫秒
```

行协议示例：

```
device_property,device_id=1,identifier=temperature num=23.5,json="23.5" 1700000000000
```

- 每 5 000 点一次 `POST /write?db=<库>&precision=ms[&rp=<保留策略>]`；204 成功。
- 过期清理由 Influx 保留策略负责，后端的每日清理只清 PG 里的事件和通信记录。
- 最新值仍在 PG，不随 `IOT_HISTORY_STORE` 改变。
- 写失败只影响历史：最新值、告警、WebSocket 推送不受影响。
- 已存在的历史数据不会在切换存储时自动迁移。

## 5. 保留策略

`TelemetryService.purgeExpired` 每天 **03:17** 删除：

| 数据 | 默认保留 | 配置 |
|---|---|---|
| 属性历史 | 90 天 | `retention.history.days` |
| 设备事件 | 90 天 | 同上 |
| 通信记录 | 7 天 | `retention.message-log.days` |

InfluxDB 的历史由保留策略控制（compose 默认 90 天，`IOT_INFLUX_RETENTION_DAYS`）。仪表盘"上报趋势"基于通信记录，所以最多回看 7 天。

## 6. 查询限制

| 接口 | 限制 |
|---|---|
| `GET /devices/{id}/history` | 分页 `size ≤ 100`；时间跨度 ≤ 7 天；`start/end` 默认最近 1 小时 |
| `GET /devices/{id}/trend` | 跨度 ≤ 31 天；每条曲线最多 2 000 点，超出自动放大聚合间隔 |
| 一次查询的属性数 | ≤ 10 |
| 分页 | `page` 从 1 开始，`size ≤ 100` |

## 7. 写入路径与表的对应

| 写入内容 | 队列 | 目标 | 代码 |
|---|---|---|---|
| 属性最新值 | `latest` | `iot_device_latest` | `TelemetryService.flushLatest/upsertLatest` |
| 属性历史 | `history` | `iot_device_property_history` 或 Influx | `PostgresHistoryStore` / `InfluxHistoryStore` |
| 事件 | 事件路径 | `iot_device_event` | `TelemetryService` |
| 通信记录 | `message-log` | `iot_device_message_log` | `MessageLogger` |
| 最后通信时间 | 内存 + 3 s 刷 | `iot_device.last_comm_at` | `DeviceRuntime.flushLastComm` |
| 在线状态 | 内存 + 事件 | `iot_device.online_status` | `DeviceRuntime.touch/sweepOffline` |

批量队列的结构、linger、丢弃语义见 [ingest.md](ingest.md) §5–§6。
