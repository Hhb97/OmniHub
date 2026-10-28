# 用 Docker 部署（PostgreSQL + EMQX + InfluxDB 1.8 + 后端 + 前端 + Prometheus/Grafana 监控）

> 状态：已在一台 Docker 28 / Compose 2.33 的 Linux 虚拟机上部署并验证：9 个容器全部运行、后端健康、EMQX 的 HTTP 认证/授权由脚本配置成功、后端的 MQTT 连接建立、Prometheus 的 6 个采集目标全部 UP、3 个 Grafana 看板加载。该环境用的是 `postgres:14-alpine`、`emqx 5.7.2`、`prometheus v2.52.0`（见 §3 的镜像覆盖）。**设备接入、页面操作和压测下的表现没有在这套部署上验证过。**

## 1. 组件与端口
| 服务 | 镜像 | 对外端口 | 说明 |
|---|---|---|---|
| frontend | 本仓库构建（nginx） | `WEB_PORT`，默认 **8088** | 页面 + 转发 `/api`、`/ws` 到后端。默认不用 80（很多主机上 80 已被占用） |
| emqx | `emqx/emqx:5.8.0` | `MQTT_PORT` 1883、`EMQX_DASHBOARD_PORT` 18083 | 设备连接；设备认证和主题授权由后端回调完成 |
| backend | 本仓库构建（JDK 21） | 不对外 | 容器内固定 8080 |
| postgres | `postgres:16` | 不对外 | 业务数据、最新值、事件、通信记录 |
| influxdb | `influxdb:1.8` | 不对外 | 属性历史（默认存这里；`IOT_HISTORY_STORE=postgres` 可改回 PG） |
| grafana | `grafana/grafana:10.4.2` | `GRAFANA_PORT`，默认 **3000** | 3 个监控看板，见 `docs/monitoring.md` |
| prometheus | `prom/prometheus:v2.53.0` | 不对外 | 采集后端、EMQX、InfluxDB、PG、主机指标 |
| postgres-exporter / node-exporter | exporter 镜像 | node-exporter 占主机 **9100** | PG 指标 / 主机指标（主机网络，请用防火墙限制） |

数据卷：`pgdata`、`influxdata`、`emqxdata`、`promdata`、`grafanadata`，容器重建不丢数据。

## 2. 主机要求
- Docker Engine 和 compose 插件（`docker compose version` 能运行）。
- 能访问镜像仓库（Docker Hub、Maven Central、npm），或配置了镜像加速；构建时会下载依赖。
- 建议 ≥ 4 核 / 8 GB（一万连接的压测另见 `docs/tuning.md` 的操作系统调优）。
- 防火墙放行 `WEB_PORT`（8088）、`MQTT_PORT`（1883）、`GRAFANA_PORT`（3000），需要时再放行 18083；**9100（node-exporter）只允许可信机器访问**。

## 3. 一键部署（从 Windows 通过 SSH）
前提：本机能用**密钥**免密 SSH 登录到主机（脚本不会输入或保存任何密码）。
```powershell
.\deploy\deploy-vm.ps1 -VmHost <主机IP> -User <SSH用户名>
```
脚本依次做：检查 SSH 和 Docker → 打包工程（排除 `node_modules`、构建产物、`.env`、`application-localext.yml` 等本地文件）→ `scp` 上传到主机 `~/omnihub` → 如果主机上没有 `.env`，让你输入**平台管理员密码**（至少 8 位，通过 SSH 加密通道传入，不出现在命令行），其余密钥随机生成并只保存在主机的 `.env`（权限 600）→ `docker compose up -d --build` → 配置 EMQX。再次运行就是“更新代码并重建”，已有 `.env` 和数据不受影响。

### 主机访问不了 Maven / npm 时：本机构建再上传
```powershell
.\deploy\deploy-vm.ps1 -VmHost <主机IP> -User <SSH用户名> -IdentityFile <私钥路径> -Prebuilt
```
加 `-Prebuilt` 后，jar 和前端包在**本机**构建（需要本机有 JDK 17+、Maven、Node），上传到主机后只用 `deploy/*.prebuilt.Dockerfile` 拼镜像，主机只需要能拉 `eclipse-temurin:21-jre` 和 `nginx` 两个基础镜像。脚本会从一份**不含 `application-localext.yml` 的副本**里打包，避免把本机的连接配置打进 jar。它会在主机 `.env` 里写入 `COMPOSE_FILE=docker-compose.yml:deploy/docker-compose.prebuilt.yml`，之后在主机上直接运行的 `docker compose` 命令也自动使用这个覆盖文件。

### 用主机上已有的镜像
`.env` 里可以覆盖镜像，不用重新拉取：`POSTGRES_IMAGE`、`EMQX_IMAGE`、`PROMETHEUS_IMAGE`（默认值见 `.env.example`）。Docker Hub 慢时在主机的 `/etc/docker/daemon.json` 配 `registry-mirrors` 并 `systemctl restart docker`；写了配置但没重启是不会生效的，用 `docker info` 能看到 Registry Mirrors 才算生效。

### 注意
- 脚本通过 SSH 密钥登录，不处理密码；脚本里的换行符会在上传后统一转成 LF（Windows 工作区的 `.sh` 是 CRLF）。
- 后端每次启动时会先报两次 `MQTT gateway cannot connect ... Not authorized`，约 10 秒后自动连上：后端在 Web 服务就绪前就开始连 EMQX，EMQX 回调后端被拒，属于启动顺序上的无害噪音。

## 4. 手动部署
```bash
# 在主机上，进入项目目录
OMNIHUB_ADMIN_PASSWORD='至少8位' sh deploy/vm-init-env.sh   # 生成 .env（已存在则跳过）
docker compose up -d --build
sh deploy/emqx-bootstrap.sh                                   # 让 EMQX 的认证/授权回调后端
docker compose ps
```
`.env` 里的变量说明见 `.env.example`。

## 5. EMQX 配置（`deploy/emqx-bootstrap.sh`）
脚本通过 EMQX 的 REST API 配置：HTTP 认证（`/api/mqtt/auth`）、HTTP 授权（`/api/mqtt/acl`）、授权无匹配时拒绝并开启结果缓存（10 分钟），HTTP 源连接池 32、请求超时 10 秒；请求头 `x-broker-token` 取自 `.env` 的 `IOT_MQTT_HTTP_AUTH_TOKEN`。重复运行是安全的（已配置的会跳过）。EMQX 回调的地址是容器网络内的 `http://backend:8080`。脚本未在真实 EMQX 上运行过；失败时会打印 EMQX 的错误响应。

## 6. 验证
```bash
docker compose ps                         # 全部 running，backend 为 healthy
docker compose logs backend | grep -E "Started IotApplication|MQTT gateway|Device auth cache|InfluxDB database"
curl -s http://localhost:8088/actuator/health   # 经前端转发（若未放行则用 curl http://backend ... 在容器内）
```
- 浏览器打开 `http://<主机IP>:8088`，用管理员账号登录。
- 监控：`http://<主机IP>:3000`（用户 admin，密码见主机 `.env` 的 `GRAFANA_ADMIN_PASSWORD`），三个看板在 OmniHub 文件夹；先在 “中间件” 看板确认各目标是 UP。
- 在页面创建产品、物模型、设备（“新增设备”弹窗里可复制接入 JSON），再用模拟器或压测程序连 `mqtt://<主机IP>:1883`。
- 压测配置里 `broker=mqtt://<主机IP>:1883`，设备 JSON 来自“批量新增设备”导出。

## 7. 日常运维
```bash
docker compose logs -f backend            # 看日志（压测时保持 INFO，默认即 INFO）
docker compose restart backend            # 改了 .env 里后端相关变量后：docker compose up -d
docker compose down                       # 停止（数据卷保留）；加 -v 才会删除数据
# 备份
docker compose exec -T postgres pg_dump -U iot iot | gzip > iot-$(date +%F).sql.gz
```
InfluxDB 的历史数据在卷 `influxdata`，保留策略默认 90 天（`IOT_INFLUX_RETENTION_DAYS`）。

## 8. 常见问题
| 现象 | 处理 |
|---|---|
| `docker compose up` 构建很慢或失败 | 主机访问 Maven/npm/Docker Hub 不通：配置镜像加速或代理 |
| 前端打不开 | 防火墙没放行 `WEB_PORT`；`docker compose ps` 看 frontend 是否运行 |
| 页面能开但登录失败 | `docker compose logs backend`；确认 `.env` 里 `IOT_ADMIN_PASSWORD` 至少 8 位（管理员只在用户表为空时创建） |
| 设备连不上（NOT_AUTHORIZED） | 先跑 `sh deploy/emqx-bootstrap.sh`；看 `docker compose logs backend` 里有没有 `Broker hook rejected`（令牌不一致）；EMQX Dashboard 的认证页确认 HTTP 认证状态为已连接 |
| 后端一直 `MQTT gateway cannot connect` | EMQX 还没配好回调（后端自己的 `platform` 账号也走回调）；运行 bootstrap 脚本 |
| 历史曲线为空 | `docker compose logs backend | grep -i influx`；InfluxDB 容器健康吗；`IOT_HISTORY_STORE` 是否为 influxdb |
| 端口冲突 | 改 `.env` 里的 `WEB_PORT`、`MQTT_PORT`、`EMQX_DASHBOARD_PORT` 后 `docker compose up -d` |

## 9. 限制
- 没有 TLS：MQTT 和 HTTP 都是明文，仅适合内网；生产请在前面加 TLS 终结（负载均衡/反向代理）。
- 单机单实例；PostgreSQL、InfluxDB 没有做高可用。
- Kafka 不在这个 compose 里（`docs/kafka-ingest.md` 说明如何连外部 Kafka）。
