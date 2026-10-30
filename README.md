# OmniHub 通用 IoT 物联网平台

前后端分离的 IoT 平台：产品 / 物模型 / 设备管理、MQTT 接入、实时监控、设备控制、历史数据、告警、仪表盘、用户权限。

```
backend/    Spring Boot 3.5 + MyBatis-Plus + Flyway + JWT + WebSocket + MQTT 网关
            多模块 Maven：platform-* 按职责拆分，只有 core 打可执行 jar（见 docs/architecture.md）
frontend/   Vue 3 + TypeScript + Vite + Element Plus + Pinia + ECharts
simulator/  独立的 MQTT 模拟设备 + 演示数据初始化 + 场景测试 (Node)
scripts/    端到端冒烟脚本、压测建号脚本、EMQX 配置脚本
deploy/     Dockerfile / nginx 配置 / 部署与监控配置
docs/       架构、上行链路、数据模型、配置、部署、运维、API、压测、历史复盘
```

## 验证状态

| 项目 | 状态 |
|---|---|
| 后端编译、`local` profile 启动、Flyway 迁移 | 已在 Windows + JDK 17 上实际运行 |
| JUnit 单元/集成测试（`backend/platform-*/src/test`、`backend/core/src/test`） | **126 项通过**（含嵌入式 PG + 嵌入式 Broker 的 `DeviceFlowIntegrationTest` 8 项） |
| 真实 MQTT 链路（认证、ACL、上报、指令、遗嘱离线） | `scripts/e2e-smoke.mjs` 29 项、`simulator/src/e2e-scenarios.js` 40 项通过；EMQX 5.7.2 上 HTTP 认证/授权也已验证 |
| 前端 lint / build | 通过；登录、仪表盘、设备实时数据、下发指令已在浏览器对真实后端手动验证 |
| Docker Compose 整套栈 | 已在 16 核 / 11G Linux VM 上部署运行：11 个容器、后端健康、EMQX 回调、Prometheus 目标、Grafana 看板 |
| 单机上行吞吐 | **15k msg/s 实测通过**（5 000 台 × 3 条/秒，120 s，16 会话 × 4096 窗口，Kafka 模式）；平台侧全量接收、broker 零丢弃 |
| InfluxDB 历史写入 | 已联调；2 个 writer 约 89k 点/s。15k × 7 属性需要 105k/s，会丢点，需把 `IOT_BATCH_HISTORY_WORKERS` 调到 4 |
| 多实例 / 高可用 / TLS | **未实现**（共享订阅能分摊上行，但去重、告警状态、消费组仍按单实例设计） |

压测的完整数据、复现方式和每一轮的前后对比见 [docs/README.md](docs/README.md)、[docs/loadtest.md](docs/loadtest.md)、[docs/perf-ingest-retro.md](docs/perf-ingest-retro.md)。

## 快速开始（本地开发，无需安装 PostgreSQL / Broker）

前置：JDK 17+、Maven 3.9+、Node 20+。`local` profile 会启动**嵌入式 PostgreSQL**（数据在 `backend/data/pgdata`）和**嵌入式 MQTT Broker**（1883），仅供开发。

```bash
# 1. 后端（默认 8080；端口被占用可加 SERVER_PORT=8081）
cd backend
mvn -DskipTests install                                  # 首次、或改过 platform-* 模块后执行一次
mvn -pl core spring-boot:run -Dspring-boot.run.profiles=local
# 开发管理员：admin / Admin@123456（仅 local profile，可用 IOT_ADMIN_PASSWORD 覆盖）。Swagger: /swagger-ui.html

# 2. 前端（5173，代理 /api 和 /ws 到后端）
cd frontend
npm install
VITE_DEV_BACKEND=http://localhost:8080 npm run dev

# 3. 创建演示产品/物模型/设备/告警规则，并生成模拟器配置（含设备密钥，已 git-ignore）
cd simulator
npm install
IOT_API=http://localhost:8080 IOT_ADMIN_PASSWORD='Admin@123456' npm run provision

# 4. 启动模拟设备
npm start            # 终端输入 offline / online / spike / quit 可模拟掉线、上线、温度尖峰
```

打开 http://localhost:5173 登录，设备详情页可看到实时数据，在"设备控制"页下发指令。

正常停止（Ctrl+C）时嵌入式 PostgreSQL 应随之关闭；被强制结束时可能残留 `postgres.exe`，处理方式见 [docs/operations.md](docs/operations.md) §4.1。

### 自动化验证

```bash
IOT_API=http://localhost:8080 IOT_ADMIN_PASSWORD='Admin@123456' node scripts/e2e-smoke.mjs        # 约 20 秒
cd simulator && IOT_API=http://localhost:8080 IOT_ADMIN_PASSWORD='Admin@123456' node src/e2e-scenarios.js   # 约 90 秒
cd backend && mvn test     # JUnit：单元测试 + 嵌入式 PG/Broker 集成测试（端口 15439 / 18830）
```

冒烟脚本需要先启动模拟器；场景脚本不需要（它自己扮演设备）。

## 生产式部署（Docker Compose）

PostgreSQL + EMQX + InfluxDB 1.8 + Kafka + 后端 + Nginx 前端 + Prometheus/Grafana：

```bash
cp .env.example .env     # 填写所有 CHANGE_ME（JWT 密钥、数据库/管理员/MQTT 密码）
docker compose up -d --build
sh deploy/emqx-bootstrap.sh
```

- 前端 `http://host:${WEB_PORT}`（默认 8088），设备 MQTT 连接 `host:${MQTT_PORT}`（1883，未启用 TLS，生产请在前面加 TLS 终结）。
- 不使用 `local` profile；管理员密码来自 `IOT_ADMIN_PASSWORD`，仅在用户表为空时创建。
- 也可以用 `deploy/deploy-vm.ps1` 从 Windows 一键部署（支持 `-Prebuilt` 在本机构建）。
- 非 compose 部署：`cd backend && mvn -P '!embedded' -pl core -am package`，再用环境变量运行 `core/target/iot-backend-*.jar`。

完整步骤、主机要求、EMQX 配置、备份升级、本机中间件接入见 [docs/deployment.md](docs/deployment.md)；全部环境变量见 [docs/configuration.md](docs/configuration.md)。

## 文档

从 [docs/README.md](docs/README.md) 进入（含阅读路径、当前实测基准、术语和旧文件对照）：

- [架构说明](docs/architecture.md)
- [上行链路：共享订阅、在途窗口、批量写入、Kafka](docs/ingest.md)
- [数据模型](docs/data-model.md)
- [配置参考（环境变量唯一权威表）](docs/configuration.md)
- [部署（Docker + 本机中间件）](docs/deployment.md)
- [运维与监控](docs/operations.md)
- [设备接入协议](docs/mqtt-protocol.md)
- [API 契约](docs/api-contract.md)
- [压测程序与 15k 复现](docs/loadtest.md)
- [性能复盘（历史）](docs/perf-ingest-retro.md)

## 已知限制

- **单机单实例**：PostgreSQL、InfluxDB、Kafka 都没有高可用；多后端实例未实现。
- **broker 重启恢复慢**：Paho 退避默认封顶 128 秒，16 条连接全部恢复要 **129 秒**；满载时这段窗口会丢消息。原因和一行修复方案见 [docs/operations.md](docs/operations.md) §3.1（当前未改）。
- **历史写入是 15k 档的瓶颈**：每个属性都写历史时需要 105k 点/s，2 个 writer 只有约 89k/s；要全量保存需把 `IOT_BATCH_HISTORY_WORKERS` 调到 4。
- **设备上下线依赖遗嘱 / `status` / 90 秒超时**：外部 Broker 没有连接事件回调，不是即时发现。
- **通信记录默认关闭**：`IOT_LOG_OK_PROPERTY=false`；仪表盘的"上报趋势 / 今日消息数 / 活跃设备"会因此偏低。打开它会把 ingest 上限压到约 4.2k msg/s。
- **最新值是快照**：按设备采样（默认 1 秒），不是审计数据；审计看历史库。
- **告警"连续 N 次"计数在内存里**：后端重启后重新计数；登录失败锁定和 JWT 退出黑名单也在内存里。
- **单租户使用**：数据按 `tenant_id` 隔离并在所有查询中强制过滤，但没有租户管理界面。
- **历史表未分区**：大表清理会慢；大规模场景应改分区或换时序库。
- **没有 TLS**：MQTT 和 HTTP 都是明文，仅适合内网。
- 未使用 Redis（没有必须的场景）。
