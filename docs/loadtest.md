# 压测程序（Java）

位置：`backend/src/test/java/com/omnihub/iot/loadtest/MqttLoadRunner.java`（不是 JUnit 测试，`mvn test` 不会运行它）。

作用：读取“批量新增设备”导出的 **设备 JSON 文件**，**每个设备一个真实 MQTT 连接**，按固定速率上报属性数据，并统计发送/确认速率、确认延迟、认证失败、重连。不访问数据库。使用 HiveMQ MQTT 客户端（Netty 异步，连接多时比 Paho 省线程）。

> 状态：已编译通过，**尚未对真实环境跑通验证**。第一次请先用少量设备（`--limit 10`）试跑。

## 1. 准备设备文件
在前端“设备管理 → 批量新增”创建设备后，点“**下载 JSON 文件**”（或“复制全部 JSON”存成文件）。格式：

```json
[
  { "name": "LT-001", "brokerUrl": "mqtt://<host>:1883", "productKey": "demo-sensor",
    "deviceKey": "dev3f9a1c07be52", "deviceSecret": "…", "clientId": "demo-sensor.dev3f9a1c07be52",
    "username": "demo-sensor.dev3f9a1c07be52", "password": "…" }
]
```
程序只用 `productKey`、`deviceKey`、`deviceSecret`（也可用 `password` 代替 `deviceSecret`），其余字段忽略。密钥只在创建时显示一次，所以创建时一定要保存文件。

## 2. 载荷（属性）
默认使用 `backend/src/test/resources/loadtest/thing-model.json` 里的属性：`temperature`、`humidity`、`switch`、`mode`、`voltage`、`power`、`targetTemp`（与演示物模型一致）。每次上报包含全部属性。

换成别的产品：用 `--model 文件` 指定物模型 JSON（完整物模型对象，或只含属性的数组）。**产品的物模型里必须有这些属性**，否则平台会拒绝未定义的属性（设备“通信记录”里标 `INVALID`）。

## 3. 运行
命令行（Windows）：
```powershell
.\scripts\loadtest.ps1 --devices D:\tmp\devices-LT.json --broker mqtt://<host>:1883 --rate 1 --duration 300
```
Git Bash / Linux 用 `scripts/loadtest.sh`，参数相同。

IDEA：运行 `MqttLoadRunner`，在 Program arguments 填：
```
--devices D:\tmp\devices-LT.json --broker mqtt://<host>:1883 --limit 10 --rate 1 --duration 60 --report 1
```
设备文件默认读运行目录下的 `devices.json`（或环境变量 `LT_DEVICES`），不想每次传参可以放一份在那里。

## 参数
| 参数 | 默认 | 说明 |
|---|---|---|
| `--devices` | `devices.json`（或 `LT_DEVICES`） | 设备 JSON 文件 |
| `--model` | 内置物模型 | 用来生成载荷的物模型 JSON |
| `--broker` | `mqtt://localhost:1883`（或 `IOT_BROKER_URL`） | EMQX 地址 |
| `--limit N` / `--offset N` | 0（全部）/ 0 | 取文件中的前 N 个、跳过前 N 个 |
| `--shard i/n` | `0/1` | 本进程只负责文件中序号 % n == i 的设备；设备数极大时开 n 个进程分担 |
| `--rate R` | `1` | **每个设备每秒上报条数**，可为小数（`0.2` = 5 秒一条） |
| `--duration S` | `0` | 运行秒数，0 = 一直运行直到 Ctrl+C |
| `--ramp N` | `200` | 每秒建立多少个新连接，避免瞬间冲击 EMQX 和认证回调 |
| `--qos` | `1` | 0 或 1（与平台一致用 1） |
| `--value-mode` | `mid` | `mid`：取值在各属性范围中间附近（避免触发告警规则）；`random`：整个范围随机 |
| `--report S` | `5` | 统计输出间隔（秒），每秒输出用 `--report 1` |
| `--csv 文件` | 无 | 每个统计间隔追加一行，便于画图对比 |

## 输出
```
[load] connected=1000/1000 sent=1000/s acked=998/s failed=0 ackAvg=4ms ackP95=11ms authFail=0 errors=0 reconnects=0
```
- `sent` / `acked`：发出 / 收到 Broker 确认（QoS1 的 PUBACK）的速率。两者长期差距大说明 Broker 或网络吃不下。
- `ackAvg` / `ackP95`：发布到 PUBACK 的耗时，**只到 EMQX 为止**，不含后端处理。后端处理速度看平台“数据总览”的 TPS 卡片或后端日志里的 `[ingest]` 行。
- `authFail`：认证被拒数（密钥不对、设备被禁用或删除、EMQX 回调后端失败等）。`errors`：其他连接错误。
- 结束时打印总发送量、平均速率、峰值连接数。

## 注意
- `id` 为 `设备序号-序号`，不会被平台的消息去重吞掉；每个设备的上报带随机相位，不会所有设备同一毫秒发送。
- 压测会真实写入数据库（历史、通信记录、最新值），结束后注意清理；通信记录每条一行，数据量增长很快。
- 本机文件句柄和线程有限，单进程几千连接以上请用 `--shard` 开多个进程，或多台机器分担。
- 告警规则、离线规则也会生效；压测结束设备断开会触发“设备离线”告警。
