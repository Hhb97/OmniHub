# OmniHub 通用 IoT 物联网平台

前后端分离的 IoT 平台：产品 / 物模型 / 设备管理、MQTT 接入、实时监控、设备控制、历史数据、告警、仪表盘、用户权限。

```
backend/    Spring Boot 3.5 + MyBatis-Plus + Flyway + JWT + WebSocket + MQTT 网关
frontend/   Vue 3 + TypeScript + Vite + Element Plus + Pinia + ECharts
simulator/  独立的 MQTT 模拟设备 + 演示数据初始化 + 场景测试 (Node)
scripts/    端到端冒烟脚本
deploy/     Dockerfile / nginx 配置
docs/       架构、API 契约、数据库、MQTT 协议、故障排查
```

## 验证状态（请先读）

| 项目 | 状态 |
|---|---|
| 后端编译、本地 profile 启动、Flyway 迁移 | 已在 Windows + JDK 17 上实际运行 |
| 真实 MQTT 链路（认证、ACL、上报、指令、遗嘱离线） | 已用嵌入式 Broker 实际验证：`scripts/e2e-smoke.mjs` 29 项、`simulator/src/e2e-scenarios.js` 40 项通过 |
| 前端 lint / build | 已通过；登录、仪表盘、设备实时数据、下发指令已在浏览器对真实后端手动验证，其余页面仅确认加载无控制台报错 |
| JUnit 单元/集成测试（`backend/src/test`） | **已编写，但尚未运行，结果未知** |
| `localext` profile、InfluxDB 历史存储、EMQX 对接脚本 | **仅编译通过，未联调**（写代码时这些服务未运行）；改动后默认 PG 历史存储路径也未重新跑测试 |
| `docker-compose.yml`、Dockerfile、EMQX 认证对接 | **未运行**（开发机无 Docker）。外部 Broker 下无法强制踢掉已连接设备 |
| Java 21 | 开发机只有 JDK 17，pom 默认 `java.version=17`，用 `-Djava.version=21` 切换，未在 21 上构建 |

## 快速开始（本地开发，无需安装 PostgreSQL / Broker）

前置：JDK 17+、Maven 3.9+、Node 20+。`local` profile 会启动**嵌入式 PostgreSQL**（数据在 `backend/data/pgdata`）和**嵌入式 MQTT Broker**（1883），仅供开发。

```bash
# 1. 后端 (默认 8080；端口被占用可加 SERVER_PORT=8081)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
# 开发管理员：admin / Admin@123456 （仅 local profile，可用 IOT_ADMIN_PASSWORD 覆盖）。Swagger: /swagger-ui.html

# 2. 前端 (5173，代理 /api 和 /ws 到后端)
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

正常停止（Ctrl+C）时嵌入式 PostgreSQL 应随之关闭（未专门验证）；被强制结束时会残留 `postgres.exe`，需手动结束（见故障排查）。

打开 http://localhost:5173 登录，设备详情页可看到实时数据，在“设备控制”页下发指令。

### 自动化验证

```bash
IOT_API=http://localhost:8080 IOT_ADMIN_PASSWORD='Admin@123456' node scripts/e2e-smoke.mjs        # 约 20 秒
cd simulator && IOT_API=http://localhost:8080 IOT_ADMIN_PASSWORD='Admin@123456' node src/e2e-scenarios.js   # 约 90 秒
cd backend && mvn test     # JUnit：单元测试 + 嵌入式 PG/Broker 集成测试（端口 15439 / 18830）
```
冒烟脚本需要先启动模拟器；场景脚本不需要（它自己扮演设备）。

## 生产式部署（Docker Compose：PostgreSQL + EMQX + 后端 + Nginx 前端）

```bash
cp .env.example .env     # 填写所有 CHANGE_ME（JWT 密钥、数据库/管理员/MQTT 密码）
docker compose up -d --build
```
- 前端 `http://host:${WEB_PORT}`，设备 MQTT 连接 `host:${MQTT_PORT}`（1883，未启用 TLS，生产请在前面加 TLS 终结）。
- 不使用 `local` profile；管理员密码来自 `IOT_ADMIN_PASSWORD`，仅在用户表为空时创建。
- 非 compose 部署：`cd backend && mvn -P '!embedded' package`，再用环境变量运行 jar，变量见 `.env.example` 与 `backend/src/main/resources/application.yml`。

## 文档
- [架构说明](docs/architecture.md)
- [API 契约](docs/api-contract.md)（运行时可在 `local` profile 下看 Swagger）
- [数据库设计](docs/database.md)
- [设备接入协议](docs/mqtt-protocol.md)
- [使用本机 PostgreSQL / EMQX / InfluxDB 1.8](docs/local-infra.md)（profile `localext`）
- [上行消息直连 vs Kafka（压测开关）](docs/kafka-ingest.md)
- [故障排查](docs/troubleshooting.md)

## 已知限制
- 设备“上下线”依赖遗嘱消息、设备 `status` 消息与超时扫描；内置 Broker 的连接事件仅在嵌入式 Broker 下生效，外部 Broker 靠 `status` 主题与心跳超时。
- 历史数据在普通表中（有索引与保留任务），未分区；大规模场景需接入时序库（替换 `TelemetryService` 的读写）。
- 阈值告警的“连续 N 次”计数在内存中，后端重启后重新计数；登录失败锁定和 JWT 退出黑名单也在内存中，仅适用单实例。
- 单租户使用：数据按 `tenant_id` 隔离并在所有查询中强制过滤，但没有租户管理界面。
- 通信记录每条消息写一行，高频场景需改为抽样或异步批量。
- 未使用 Redis（没有必须的场景）。
