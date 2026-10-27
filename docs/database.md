# 数据库设计

PostgreSQL 14+（用 16 开发）。结构由 Flyway 管理：`backend/src/main/resources/db/migration/`（`V1__init_schema.sql` 表结构，`V2__seed_permissions.sql` 租户、权限目录、内置角色、系统配置）。管理员用户不在迁移里，由启动时 `AdminInitializer` 根据 `IOT_ADMIN_PASSWORD` 创建。

约定：时间用 `timestamptz`（UTC）；业务表用 `deleted smallint` 逻辑删除，唯一约束是带 `WHERE deleted = 0` 的部分唯一索引（删除后标识可复用）；JSON 用 `jsonb`；连接串带 `stringtype=unspecified` 以便把字符串写入 jsonb 列。

## 业务/配置表
| 表 | 说明 |
|---|---|
| `sys_tenant` | 租户（目前只有 default） |
| `sys_user` `sys_role` `sys_permission` `sys_user_role` `sys_role_permission` | 用户、角色、权限码（MENU/API）及关联 |
| `sys_config` | 键值配置：离线超时、指令超时、保留天数 |
| `sys_operation_log` `sys_login_log` | 审计日志（`created_at` 倒序索引） |
| `iot_product` | 产品；`product_key` 唯一；`thing_model_version` 当前版本 |
| `iot_thing_model` | 物模型版本，`(product_id, version)` 唯一，`content` jsonb = `{properties,events,services}`，与设备数据分离 |
| `iot_device` | 设备；`(product_id, device_key)` 唯一；`secret_hash/secret_salt`（不存明文）；`mqtt_connected`、`online_status`、`last_online_at/last_offline_at/last_comm_at`；索引 `(tenant_id, online_status)` |
| `iot_device_command` | 指令；`command_no` 唯一；状态与各时间点；`timeout_at`，对未终态行建部分索引供超时扫描 |
| `alarm_rule` | 告警规则（范围/类型/阈值/连续次数/静默/级别） |
| `alarm_record` | 告警记录；**部分唯一索引** `(rule_id, device_id) WHERE status='ACTIVE'` 防重复 |
| `alarm_action` | 告警处理记录（TRIGGER/ACK/COMMENT/RECOVER） |

## 设备数据表（高写入，无外键、只追加）
| 表 | 说明 | 索引 |
|---|---|---|
| `iot_device_latest` | 每设备每属性最新值，主键 `(device_id, identifier)`，upsert | 主键 |
| `iot_device_property_history` | 历史属性；`num_value` 为数值投影（布尔 0/1）供聚合 | `(device_id, identifier, ts DESC)`、`(ts)` |
| `iot_device_event` | 设备事件 | `(device_id, ts DESC)` |
| `iot_device_message_log` | 通信记录（payload 脱敏并截断 2000 字符） | `(device_id, created_at DESC)`、`(created_at)` |

高频上报路径只涉及批量 INSERT + 主键 upsert，没有关联查询；设备身份解析走 60 秒内存缓存。

## 保留策略
`TelemetryService.purgeExpired` 每天 03:17 删除：历史与事件超过 `retention.history.days`（默认 90），通信记录超过 `retention.message-log.days`（默认 7）的数据。仪表盘“上报趋势”基于通信记录，所以最多回看 7 天。大数据量下应改为按月分区或接入时序库。

## 查询限制
历史列表分页（size ≤ 100）且时间跨度 ≤ 7 天；趋势跨度 ≤ 31 天且每条曲线最多 2000 点（超出自动放大聚合间隔）；一次最多查 10 个属性。
