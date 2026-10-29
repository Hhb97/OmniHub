# OmniHub 文档

本目录描述**当前代码的状态**。只有 [`perf-ingest-retro.md`](perf-ingest-retro.md) 是历史记录（"当时怎么定位的"），其余文档都以最新一次实测和仓库默认值为准。

## 文档地图

| 文档 | 回答什么问题 |
|---|---|
| [architecture.md](architecture.md) | 系统由哪些部分组成，一次上报/指令在代码里怎么流动 |
| [deployment.md](deployment.md) | 怎么用 Docker 部署；怎么接本机已有的 PostgreSQL / EMQX / InfluxDB |
| [configuration.md](configuration.md) | **全部环境变量的唯一权威表**：默认值、作用、什么时候该改 |
| [ingest.md](ingest.md) | 上行链路：多连接共享订阅、在途窗口、处理队列、批量写入、Kafka 模式、容量公式 |
| [data-model.md](data-model.md) | 表结构、最新值的 JSONB 设计、InfluxDB 模型、保留策略、查询限制 |
| [mqtt-protocol.md](mqtt-protocol.md) | 设备侧协议：连接参数、Topic、上行/下行报文、指令状态机 |
| [api-contract.md](api-contract.md) | 前后端 HTTP / WebSocket 契约 |
| [loadtest.md](loadtest.md) | 压测程序怎么用，怎么复现 15k msg/s |
| [operations.md](operations.md) | 监控看什么、积压怎么判断、故障排查、已知行为 |
| [perf-ingest-retro.md](perf-ingest-retro.md) | 历史复盘：七步排除法、每一轮的数字、走过的弯路 |

## 阅读路径

- **第一次接手**：architecture → ingest → data-model → api-contract
- **要部署一套**：deployment → configuration → operations
- **要压测/调吞吐**：ingest（§3 容量公式）→ configuration → loadtest → operations（§2 积压判断）
- **设备端对接**：mqtt-protocol → api-contract（指令部分）
- **出问题了**：operations §4 故障排查；先看"哪一层在积压"再看数据库

## 当前实测基准

单机、VM 16 核 / 11 GB，EMQX 5.7.2，后端与 EMQX 同机。压测客户端是 `MqttLoadRunner`，每台设备一条真实 MQTT 连接，QoS 1。

| 场景 | 关键配置 | 结果 | 瓶颈/备注 |
|---|---|---|---|
| 5 000 台 × 3 条/秒 = **15k msg/s**，120 s（`IOT_INGEST_MODE=kafka`） | 16 连接 × 4096 broker 窗口，`IOT_LOG_OK_PROPERTY=false` | 后端处理 **1 574 498** 条，等于 EMQX `messages_sent`；mqueue 全程 **0**；在途峰值 4 879/65 536；broker 零丢弃 | **平台侧全通**。客户端仍有 36 584 次（2.5%）发送失败、ackP95 3.56 s，是长尾下客户端打满自己的节拍，不是后端少收。唯一在丢的是 `history`：15k × 7 属性 = 105k 点/s，2 个 writer 只能写约 89k/s，丢 153 万点 |
| 10 000 台 × 1 条/秒，120 s | 8 连接 × 1024，日志关 | **9.4k msg/s**；客户端 failed 34 204 → 0；ackP95 9.8 s → 19 ms；broker 不再丢 | 同档位下 8 与 16 连接等价；更高速率会撞窗口上限 |
| 同上，但提到 12k / 15k | 8 连接 × 1024 | 后端只有 **4.6k msg/s**，broker 会话 inflight 顶在 8 192 | 窗口用满，mqueue 涨到 40 万；下游全空闲 |
| 10 000 台 × 1 条/秒，恢复旧默认 | 4 连接，`IOT_LOG_OK_PROPERTY=true` | **2.0k msg/s**，客户端失败 39 990 条 | `iot_device_message_log` 上限约 4.2k 行/s，且约 13 GB/小时 |
| 单连接订阅全部上行 | 1 连接 | 约 **6k msg/s** 触顶 | 单会话 mqueue 满 → `dropped_msg_due_to_mqueue_is_full` → `mailbox_overflow` 断连 |

复现方式见 [loadtest.md](loadtest.md)；每一轮的前后对比见 [perf-ingest-retro.md](perf-ingest-retro.md)。

当前仓库默认值就是第一行（16 × 4096、日志关）。**但 `IOT_BATCH_HISTORY_WORKERS` 仍是 2**：跑满 15k 且每个属性都写历史时，InfluxDB 会丢点，需要改成 4（见 [ingest.md](ingest.md) §8）。

## 术语

| 词 | 含义 |
|---|---|
| **上行 / uplink** | 设备 → EMQX → 平台：`property/post`、`event/post`、`status`、`command/reply` |
| **连接 / session** | 平台到 broker 的一条 MQTT 连接。一条连接 = 一个 broker 会话 = 一个投递队列 = 一个 Paho 回调线程 |
| **共享订阅** | `$share/<group>/<filter>`；组内每条消息只投给**一个**订阅者，用来把上行分摊到多条连接 |
| **在途窗口 / inflight** | broker 侧每个订阅会话最多有多少条未确认的 QoS1 消息（`EMQX_MAX_INFLIGHT`）；平台侧的 `IOT_MQTT_MAX_INFLIGHT` 是 Paho 的**发送**窗口，不限制接收 |
| **mqueue** | broker 侧每个会话的待投递队列；窗口满了以后消息在这里排队，再满就丢 |
| **lane** | 后端进程内的单线程处理队列，按设备哈希固定，用来保证同一设备有序 |
| **批量队列 / batch queue** | `latest`、`history`、`message-log` 三个内存队列，后台线程成批写库 |
| **最新值** | `iot_device_latest`，一台设备一行 JSONB，是"当前快照"，不是审计数据 |
| **历史** | `iot_device_property_history`（PG）或 InfluxDB `device_property`，每个样本一行/一点 |
| **direct / kafka** | 上行进入处理器的两种方式：进程内 lane，或先写 Kafka 再消费 |

## 旧文件 → 新文件

重构前进来的链接按这张表跳转：

| 旧文件 | 现在的位置 |
|---|---|
| `docs/tuning.md` | 环境变量 → [configuration.md](configuration.md)；吞吐/窗口/共享订阅 → [ingest.md](ingest.md)；重启恢复等已知行为 → [operations.md](operations.md) §3 |
| `docs/batch-write.md` | 批写与各存储的写入路径 → [ingest.md](ingest.md) §4–§6；表结构 → [data-model.md](data-model.md) |
| `docs/kafka-ingest.md` | [ingest.md](ingest.md) §7 |
| `docs/local-infra.md` | [deployment.md](deployment.md) §2 |
| `docs/deploy-docker.md` | [deployment.md](deployment.md) §1 |
| `docs/monitoring.md` | [operations.md](operations.md) §1–§2 |
| `docs/troubleshooting.md` | [operations.md](operations.md) §4 |
| `docs/database.md` | [data-model.md](data-model.md) |
