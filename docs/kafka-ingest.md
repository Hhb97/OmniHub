# 上行消息：直连 vs 经 Kafka（压测对比开关）

配置项 `iot.ingest.mode`（环境变量 `IOT_INGEST_MODE`），**重启后端生效**。

| 模式 | 链路 |
|---|---|
| `direct`（默认） | 设备 → EMQX → 后端 MQTT 客户端 → 按设备哈希的 8 条内存队列 → `UplinkProcessor`（校验、入库、告警、推送） |
| `kafka` | 设备 → EMQX → 后端 MQTT 客户端 → **转发到 Kafka**（key = `productKey.deviceKey`）→ 后端 Kafka 消费者 → 同一个 `UplinkProcessor` |

两种模式后面的业务处理完全相同，差别只在“消息怎么进入处理器”。下行指令（后端 → 设备）两种模式都直接发布到 EMQX，不经过 Kafka。

## 配置（环境变量）
| 变量 | 默认 | 说明 |
|---|---|---|
| `IOT_INGEST_MODE` | `direct` | `direct` 或 `kafka` |
| `IOT_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka 地址，如 `<host>:9092` |
| `IOT_KAFKA_TOPIC` | `iot-uplink` | 上行 topic |
| `IOT_KAFKA_GROUP` | `iot-platform` | 消费组 |
| `IOT_KAFKA_CONCURRENCY` | `8` | 消费线程数，超过分区数无意义 |
| `IOT_KAFKA_PARTITIONS` | `8` | 自动建 topic 时的分区数 |
| `IOT_KAFKA_CREATE_TOPIC` | `true` | 启动时若 topic 不存在则创建；Kafka 禁止自动建 topic 或没有权限时设 `false` 并手动创建 |
| `IOT_KAFKA_REPLICAS` | `1` | 自动建 topic 的副本数 |
| `IOT_KAFKA_ACKS` | `1` | 生产者确认级别：`0` 最快但可能丢、`1`、`all` 最可靠 |

例：
```powershell
$env:IOT_INGEST_MODE = "kafka"
$env:IOT_KAFKA_BOOTSTRAP = "<host>:9092"
mvn spring-boot:run "-Dspring-boot.run.profiles=localext"
```
更多生产者/消费者参数在 `application.yml` 的 `spring.kafka` 下（压缩 lz4、`linger.ms=5`、`max.block.ms=3000`、`max-poll-records=500`），可按压测需要调整。

## 压测时看什么
后端每 10 秒（仅在有消息时）打印一行统计：
```
[ingest] mode=kafka processed=48210 rate=4821/s avgDelay=12ms maxDelay=240ms droppedAtForward=0 totalProcessed=...
```
- `rate`：这 10 秒内每秒处理的消息数（进入处理器的速度）。
- `avgDelay` / `maxDelay`：从后端 MQTT 客户端收到消息，到开始处理的耗时。`direct` 下是内存队列等待时间；`kafka` 下还包含 Kafka 往返和消费排队。
- `droppedAtForward`：仅 `kafka` 模式，转发到 Kafka 失败而丢弃的消息数（Kafka 不可用、缓冲区满等）。
- 注意 `delay` 只到“开始处理”，不含入库耗时。需要端到端数据，请对比设备发送时间与数据库/Influx 里的落库时间，或观察数据库写入速率。

**对比建议**：两种模式用同一批设备、同样的上报频率和同一台机器；每种模式先预热 1～2 分钟再取数；同时观察 EMQX、后端、Kafka 所在虚拟机的 CPU 和内存，以及 PostgreSQL/InfluxDB 的写入负载——很多时候瓶颈在入库而不是消息通道。

## 语义差异与注意
- **顺序**：同一设备的消息 key 相同，进同一分区，消费时保持顺序；`direct` 下同一设备也落在同一条内存队列。
- **可靠性**：`direct` 下后端宕机时内存队列里的消息会丢。`kafka` 下已写入 Kafka 的消息在后端重启后继续消费（默认 `auto-offset-reset=latest`，新的消费组不会补历史消息）；但“MQTT 收到 → 写入 Kafka”这一步失败仍会丢，并计入 `droppedAtForward`。
- **去重**：设备消息 `id` 的内存去重在两种模式下都有，后端重启后失效。
- **消息格式**：Kafka 中是 JSON 文本 `{"t":"<MQTT topic>","p":"<原始 payload 文本>","r":<后端收到时间 ms>}`，只支持文本 payload。
- **多实例**：本次没有实现、也没有验证。多个后端实例各自订阅 MQTT 时，每个实例都会收到全部消息，`kafka` 模式下会把同一条消息重复转发进 Kafka；要多实例需要在 EMQX 侧用共享订阅（`$share/组名/iot/+/+/...`）并相应修改订阅主题。

## 验证状态
代码已编译，且 `direct`（默认）模式启动正常。**`kafka` 模式没有连接真实 Kafka 跑过**（编写时没有可用的 Kafka），所以转发、消费、自动建 topic 的行为都未实测；遇到问题请把后端日志发过来。
