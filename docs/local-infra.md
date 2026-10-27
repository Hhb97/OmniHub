# 使用本机已安装的 PostgreSQL / EMQX / InfluxDB 1.8

使用 profile `localext`（`backend/src/main/resources/application-localext.yml`）。该配置里**没有任何密码**，全部来自环境变量。

> 状态：此套接入**没有**在真实的 EMQX / InfluxDB 上验证过（编写时这些服务没有运行）。PostgreSQL 路径与已验证的嵌入式 PG 使用同一套 SQL 和迁移。InfluxDB 的写入/查询按 1.x HTTP API 文档编写，首次联调请重点看这一块。

## 1. PostgreSQL（≥ 14）
```sql
CREATE USER iot WITH PASSWORD '<自己设定>';
CREATE DATABASE iot OWNER iot;
```
表结构由后端启动时 Flyway 自动迁移（用户需要在该库建表的权限）。业务表、最新值、事件、通信记录都在这里。

## 2. InfluxDB 1.8（属性历史数据）
默认 `IOT_HISTORY_STORE=influxdb`。数据模型：measurement `device_property`，tag `device_id`、`identifier`，字段 `num`（浮点，仅数值/布尔）和 `json`（原始值 JSON 文本）。

- `IOT_INFLUX_CREATE_DB=true`（默认）时后端启动会执行 `CREATE DATABASE`，并按 `IOT_INFLUX_RETENTION_DAYS`（默认 90）创建默认保留策略；Influx 用户需有管理权限。也可手动：
  ```
  CREATE DATABASE "iot"
  CREATE RETENTION POLICY "iot_90d" ON "iot" DURATION 90d REPLICATION 1 DEFAULT
  ```
  并把 `IOT_INFLUX_CREATE_DB=false`。若 Influx 开了认证，设置 `IOT_INFLUX_USER` / `IOT_INFLUX_PASSWORD`。
- 过期清理交给 Influx 保留策略（后端的每日清理对 Influx 不做事，只清 PG 里的事件和通信记录）。
- 最新值仍在 PG（`iot_device_latest`）；历史曲线/历史表读 Influx。Influx 写入失败只记错误日志，不影响最新值和告警。
- 想退回 PG 存历史：`IOT_HISTORY_STORE=postgres`。已存在的历史数据不会自动迁移。

## 3. EMQX
后端通过 `tcp://localhost:1883` 以账号 `platform` 连接；设备认证与主题 ACL 由 EMQX 回调后端 `/api/mqtt/auth`、`/api/mqtt/acl` 决定（请求头 `X-Broker-Token`）。`platform` 账号也由后端校验，所以 EMQX 里不用建用户。

### EMQX 5.x（脚本）
```bash
EMQX_DASHBOARD_USER=admin EMQX_DASHBOARD_PASSWORD=... IOT_MQTT_HTTP_AUTH_TOKEN=<与后端相同> \
IOT_BACKEND_URL=http://127.0.0.1:8080 node scripts/emqx-setup.mjs
```
等价于在 Dashboard 的“访问控制”里添加 HTTP 认证（POST，Body: `clientid/username/password`）和 HTTP 授权源（POST，Body: `clientid/username/topic/action`），Header 加 `x-broker-token`，并把授权“无匹配时”设为拒绝。EMQX 必须能访问 `IOT_BACKEND_URL`。

### EMQX 4.x
使用 `emqx_auth_http` 插件：`auth.http.auth_req`、`auth.http.acl_req` 指向上述两个地址，POST 参数 `clientid=%c&username=%u&password=%P`（ACL: `access=%A&username=%u&clientid=%c&topic=%t`）。注意 4.x 的响应约定是 HTTP 200/4xx 而不是 JSON `{"result":...}`，**当前后端接口按 5.x 格式返回，4.x 需要另加适配**。请告知版本以便适配。

## 4. 启动
```bash
export IOT_DB_PASSWORD=...            # PG 用户 iot 的密码
export IOT_JWT_SECRET=$(openssl rand -base64 48)
export IOT_ADMIN_PASSWORD=...         # 首个管理员，用户表为空时才创建
export IOT_MQTT_PASSWORD=...          # 后端连接 EMQX 用的 platform 账号密码（自己定，后端校验）
export IOT_MQTT_HTTP_AUTH_TOKEN=...   # 与 EMQX 脚本/配置里一致
# 可选: IOT_DB_URL IOT_DB_USER IOT_INFLUX_URL IOT_INFLUX_DB IOT_INFLUX_USER IOT_INFLUX_PASSWORD IOT_MQTT_BROKER_URL
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=localext
```
Windows PowerShell 用 `$env:IOT_DB_PASSWORD='...'` 设置变量。之后前端、`npm run provision`（`IOT_BROKER_URL=mqtt://localhost:1883`）、模拟器的步骤与 README 相同。

## 常见问题
- 后端日志 `MQTT gateway cannot connect`：EMQX 没起、端口不对，或 EMQX 回调后端被拒（`X-Broker-Token` 不一致 → 401）。
- 设备连得上但收不到指令：EMQX 授权源没生效，或 `no_match` 仍是 allow/ACL 回调报错，查看 EMQX 日志。
- 历史曲线为空但最新值有：看后端日志 `Writing property history failed`，多为 Influx 地址、库名或认证问题。
