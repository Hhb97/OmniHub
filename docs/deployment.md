# 部署

两种方式：

- **Docker Compose（推荐）**：PostgreSQL + EMQX + InfluxDB + Kafka + 后端 + 前端 + Prometheus/Grafana，一条命令起整套。
- **本机已有中间件（`localext` profile）**：后端裸跑，连本机已安装的 PostgreSQL / EMQX / InfluxDB，适合开发联调。

环境变量的完整说明见 [configuration.md](configuration.md)，这里只讲"怎么装、怎么验、怎么升级"。

## 1. Docker Compose

### 1.1 组件与端口

| 服务 | 镜像 | 对外端口 | 说明 |
|---|---|---|---|
| frontend | 本仓库构建（nginx） | `WEB_PORT`，默认 **8088** | 页面 + 转发 `/api`、`/ws` 到后端。默认不用 80 |
| emqx | `emqx/emqx:5.8.0`（可覆盖） | `MQTT_PORT` 1883、`EMQX_DASHBOARD_PORT` 18083 | 设备接入；认证/授权回调后端 |
| backend | 本仓库构建（JDK 21） | 不对外 | 容器内固定 8080 |
| postgres | `postgres:16` | 不对外 | 业务数据、最新值、事件、通信记录 |
| influxdb | `influxdb:1.8` | 不对外 | 属性历史（默认存这里；`IOT_HISTORY_STORE=postgres` 可改回 PG） |
| kafka | `apache/kafka:3.9.0` | 不对外 | 单节点 KRaft；`IOT_INGEST_MODE=kafka` 时使用 |
| kafka-exporter | `danielqsj/kafka-exporter:v1.8.0` | 不对外 | 偏移量、消费积压 |
| grafana | `grafana/grafana:10.4.2` | `GRAFANA_PORT`，默认 **3000** | 看板见 [operations.md](operations.md) |
| prometheus | `prom/prometheus:v2.53.0` | 不对外 | 采集后端、EMQX、InfluxDB、PG、Kafka、主机 |
| postgres-exporter / node-exporter | exporter 镜像 | node-exporter 占主机 **9100** | 主机网络，请用防火墙限制 |

数据卷：`pgdata`、`influxdata`、`emqxdata`、`kafkadata`、`promdata`、`grafanadata`。容器重建不丢数据。

> compose 默认 `EMQX_IMAGE=emqx/emqx:5.8.0`，当前部署机实际运行的是 **5.7.2**（本地已有镜像）。共享订阅配置在 5.7+ 的位置相同（`mqtt.*`），本文与 compose 的写法对两个版本都成立。用 `docker compose exec emqx emqx ctl status` 看实际版本。

### 1.2 主机要求

- Docker Engine + compose 插件（`docker compose version` 能运行）。
- 能访问 Docker Hub、Maven Central、npm，或配置了镜像加速。
- 建议 ≥ 4 核 / 8 GB；**15k msg/s 的实测环境是 16 核 / 11 GB**。
- 防火墙放行 `WEB_PORT`、`MQTT_PORT`、`GRAFANA_PORT`，需要时放行 18083；**9100 只允许可信机器访问**。
- 一万连接的 `ulimit -n` 至少 ≥ 连接数 × 2，`net.core.somaxconn` 不小于 `EMQX_TCP_BACKLOG`。

### 1.3 一键部署（从 Windows 通过 SSH）

前提：本机能用**密钥**免密 SSH 登录主机（脚本不处理密码）。

```powershell
.\deploy\deploy-vm.ps1 -VmHost <主机IP> -User <SSH用户名>
```

脚本依次做：

1. 检查 SSH 和 Docker；
2. 打包工程（排除 `node_modules`、构建产物、`.env`、`application-localext.yml` 等本地文件）；
3. `scp` 到主机 `~/omnihub`；
4. 主机上没有 `.env` 时，让你输入**平台管理员密码**（≥ 8 位，通过 SSH 加密通道传入，不出现在命令行），其余密钥随机生成并只写进主机 `.env`（权限 600）；
5. `docker compose up -d --build`；
6. 运行 `deploy/emqx-bootstrap.sh` 配置 EMQX 认证/授权。

再次运行就是"更新代码并重建"，已有 `.env` 和数据不受影响。

**主机访问不了 Maven / npm 时**，在本机构建再上传：

```powershell
.\deploy\deploy-vm.ps1 -VmHost <主机IP> -User <SSH用户名> -IdentityFile <私钥路径> -Prebuilt
```

`-Prebuilt` 在本机构建 jar 和前端包（需要 JDK 17+、Maven、Node），主机只用 `deploy/*.prebuilt.Dockerfile` 拼镜像，只需要能拉 `eclipse-temurin:21-jre` 和 `nginx`。脚本会把 `COMPOSE_FILE=docker-compose.yml:deploy/docker-compose.prebuilt.yml` 写进主机 `.env`。

**用主机上已有的镜像**：在 `.env` 里覆盖 `POSTGRES_IMAGE`、`EMQX_IMAGE`、`PROMETHEUS_IMAGE` 等，不必重新拉取。

### 1.4 手动部署

```bash
# 在主机上，进入项目目录
OMNIHUB_ADMIN_PASSWORD='至少8位' sh deploy/vm-init-env.sh   # 生成 .env（已存在则跳过）
docker compose up -d --build
sh deploy/emqx-bootstrap.sh                                 # 让 EMQX 的认证/授权回调后端
docker compose ps
```

`.env` 的变量说明见 [configuration.md](configuration.md) 与 `.env.example`。

### 1.5 EMQX 配置（`deploy/emqx-bootstrap.sh`）

脚本通过 EMQX REST API 配置：

- HTTP 认证 → `/api/mqtt/auth`
- HTTP 授权 → `/api/mqtt/acl`
- 授权无匹配时 **deny**，开启结果缓存 10 分钟
- HTTP 源连接池 32、请求超时 10 秒
- 请求头 `x-broker-token` = `.env` 的 `IOT_MQTT_HTTP_AUTH_TOKEN`
- 回调地址是容器网络内的 `http://backend:8080`

重复运行是安全的（已配置的会跳过）；失败时打印 EMQX 的错误响应。已在真实 EMQX 5.7.2 上验证：设备认证、Topic ACL、平台账号 `platform` 都走同一套回调。

### 1.6 验证

```bash
docker compose ps                          # 全部 running，backend 为 healthy
docker compose logs backend | grep -E "Started IotApplication|MQTT gateway|Device auth cache|InfluxDB"
curl -s http://localhost:8088/actuator/health
```

- 浏览器打开 `http://<主机IP>:8088`，用管理员账号登录。
- Grafana `http://<主机IP>:3000`（admin / `.env` 的 `GRAFANA_ADMIN_PASSWORD`）；先看"中间件"看板确认目标 UP。
- 页面创建设备后，用模拟器或压测程序连 `mqtt://<主机IP>:1883`。
- 上行连接数：`iot_mqtt_gateway_connections` 应等于 `iot_mqtt_gateway_connections_expected`（默认 16）。恢复满需要时间，见 [operations.md](operations.md) §3.1。

**启动时的无害噪音**：后端会在 Web 服务就绪前就开始连 EMQX，EMQX 回调被拒，日志出现两次 `MQTT gateway cannot connect ... Not authorized`，约 10 秒后自动连上。

### 1.7 日常运维

```bash
docker compose logs -f backend            # 压测时保持 INFO
docker compose up -d backend              # 改了 .env 里后端相关变量后（restart 不会重读 .env）
docker compose up -d emqx                 # 改了 EMQX 相关变量后
docker compose down                       # 停止，数据卷保留；加 -v 才删数据

# 备份 PostgreSQL
docker compose exec -T postgres pg_dump -U iot iot | gzip > iot-$(date +%F).sql.gz
```

InfluxDB 历史在卷 `influxdata`，保留策略默认 90 天（`IOT_INFLUX_RETENTION_DAYS`）。

### 1.8 限制

- **没有 TLS**：MQTT 和 HTTP 都是明文，仅适合内网；生产请在前面加 TLS 终结。
- **单机单实例**：PostgreSQL、InfluxDB、Kafka 都没有高可用（Kafka 单节点、副本数 1）。
- 多后端实例未实现（共享订阅能分摊消息，但去重、告警状态、Kafka 消费组仍按单实例设计）。

## 2. 本机已有中间件（`localext` profile）

`backend/src/main/resources/application-localext.yml` 里**没有任何密码**，全部来自环境变量。

### 2.1 PostgreSQL（≥ 14）

```sql
CREATE USER iot WITH PASSWORD '<自己设定>';
CREATE DATABASE iot OWNER iot;
```

表结构由后端启动时 Flyway 自动迁移（用户需要建表权限）。业务表、最新值、事件、通信记录都在这里。

### 2.2 InfluxDB 1.8（可选，属性历史）

默认 `IOT_HISTORY_STORE=influxdb`（`localext` 里）。

```sql
CREATE DATABASE "iot"
CREATE RETENTION POLICY "iot_90d" ON "iot" DURATION 90d REPLICATION 1 DEFAULT
```

- 也可以让后端启动时创建：`IOT_INFLUX_CREATE_DB=true`，并按 `IOT_INFLUX_RETENTION_DAYS` 建保留策略；Influx 用户需要有管理权限。
- 开了认证时设置 `IOT_INFLUX_USER` / `IOT_INFLUX_PASSWORD`。
- 数据模型见 [data-model.md](data-model.md) §4。
- 想退回 PG 存历史：`IOT_HISTORY_STORE=postgres`；已有历史不会自动迁移。

### 2.3 EMQX

后端通过 `tcp://localhost:1883` 以账号 `platform` 连接；设备认证与 Topic ACL 由 EMQX 回调后端 `/api/mqtt/auth`、`/api/mqtt/acl` 决定（请求头 `X-Broker-Token`）。`platform` 账号也由后端校验，EMQX 里不用建用户。

**EMQX 5.x**：

```bash
EMQX_DASHBOARD_USER=admin EMQX_DASHBOARD_PASSWORD=... IOT_MQTT_HTTP_AUTH_TOKEN=<与后端相同> \
IOT_BACKEND_URL=http://127.0.0.1:8080 node scripts/emqx-setup.mjs
```

等价于在 Dashboard 的"访问控制"里添加：

- HTTP 认证（POST，Body: `clientid/username/password`）
- HTTP 授权源（POST，Body: `clientid/username/topic/action`）
- Header 加 `x-broker-token`
- 授权"无匹配时"设为拒绝

EMQX 必须能访问 `IOT_BACKEND_URL`。

**EMQX 4.x**：使用 `emqx_auth_http` 插件，`auth.http.auth_req`、`auth.http.acl_req` 指向上述地址，POST 参数 `clientid=%c&username=%u&password=%P`（ACL: `access=%A&username=%u&clientid=%c&topic=%t`）。注意 4.x 的响应约定是 HTTP 200/4xx 而不是 JSON `{"result":...}`，**当前后端接口按 5.x 格式返回，4.x 需要另加适配**。

### 2.4 启动

```bash
export IOT_DB_PASSWORD=...            # PG 用户 iot 的密码
export IOT_JWT_SECRET=$(openssl rand -base64 48)
export IOT_ADMIN_PASSWORD=...         # 首个管理员，用户表为空时才创建
export IOT_MQTT_PASSWORD=...          # platform 账号密码（自己定，后端校验）
export IOT_MQTT_HTTP_AUTH_TOKEN=...   # 与 EMQX 脚本/配置一致

cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=localext
```

可选：`IOT_DB_URL`、`IOT_DB_USER`、`IOT_INFLUX_URL`、`IOT_INFLUX_DB`、`IOT_INFLUX_USER`、`IOT_INFLUX_PASSWORD`、`IOT_MQTT_BROKER_URL`。

Windows PowerShell 用 `$env:IOT_DB_PASSWORD='...'`。之后前端、`npm run provision`（`IOT_BROKER_URL=mqtt://localhost:1883`）、模拟器的步骤与根 [README](../README.md) 相同。

### 2.5 常见问题

| 现象 | 处理 |
|---|---|
| `MQTT gateway cannot connect` | EMQX 没起、端口不对，或回调被拒（`X-Broker-Token` 不一致 → 401） |
| 设备连得上但收不到指令 | EMQX 授权源没生效，或 `no_match` 仍是 allow / ACL 回调报错 |
| 历史曲线为空但最新值有 | 看后端日志 `Writing property history failed`；多为 Influx 地址、库名或认证问题 |

更多故障见 [operations.md](operations.md) §4。
