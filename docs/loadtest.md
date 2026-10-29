# 压测程序（Java）

位置：`backend/src/test/java/com/omnihub/iot/loadtest/MqttLoadRunner.java`（不是 JUnit 测试，`mvn test` 不会运行它）。

作用：读取“批量新增设备”导出的 **设备 JSON 文件**，**每个设备一个真实 MQTT 连接**，按固定速率上报属性数据，并统计发送/确认速率、确认延迟、认证失败、重连。不访问数据库。使用 HiveMQ MQTT 客户端（Netty 异步，连接多时比 Paho 省线程）。

> 状态：已在 16 核 VM 上跑通，单进程最多 5 000 台设备（5 000 条连接）、15k msg/s 目标速率持续 120 秒；broker 零丢弃，后端处理量与 EMQX `messages_sent` 完全一致（客户端自身仍有约 2.5% 的长尾失败，见 §"复现 15k"）。第一次仍建议先用少量设备（`--limit 10`）试跑。

## 0. 用脚本一次性创建产品、物模型和大量设备（推荐）
`scripts/loadtest-setup.mjs`（需要 Node 18+）通过平台接口：创建产品（已存在则复用）→ 保存物模型（默认 `backend/src/test/resources/loadtest/thing-model.json`）→ 分批批量创建设备 → 生成压测程序要读的设备 JSON。
```powershell
node scripts/loadtest-setup.mjs --base http://<主机>:8088 --broker mqtt://<主机>:1883 --count 10000
```
管理员密码在终端里隐藏输入（或设环境变量 `OMNIHUB_PASSWORD`），不会写入任何文件。默认输出 `backend/devices-<数量>.local.json`（`*.local.json` 已被 git 忽略）。

| 参数 | 默认 | 说明 |
|---|---|---|
| `--base` | `http://localhost:8088` | 平台地址（经前端 nginx 或直接后端端口） |
| `--broker` | 必填 | 写进设备文件的 MQTT 地址，压测程序连这个 |
| `--count` | `10000` | 设备数量 |
| `--prefix` | `LT` | 设备名前缀；多批创建时名称为 `LT-01-001`、`LT-01-002` … `LT-20-500`（前缀 + 批号 + 序号） |
| `--chunk` | `500` | 每个请求创建多少台 |
| `--product-key` / `--product-name` | `loadtest-sensor` / `LoadTest Sensor` | 产品；已存在且已有设备时脚本会拒绝，防止重复创建，要追加用 `--append` 并换 `--prefix` |
| `--model` | 内置压测物模型 | 物模型 JSON |
| `--out` | `backend/devices-<数量>.local.json` | 设备文件 |

**设备密钥只在创建时返回一次**，所以每批创建后立即追加到 `<out>.partial`，全部成功后才生成最终文件。中途失败时凭证在 `.partial` 里；超时的那一批可能已在服务端创建了设备但脚本没拿到密钥，需要在页面上删掉多余的设备再重来。设备文件含密钥，不要提交、不要分享。

## 1. 准备设备文件（用页面手动创建时）
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

## 3. 配置文件（推荐）
把 `backend/loadtest.properties.example` 复制为 `backend/loadtest.properties`（已被 git 忽略，可以放你的内网地址），填好 Broker、设备文件、速率等；之后运行不用再带参数。键名就是命令行选项去掉 `--`，**命令行参数优先于文件**。查找顺序：`--config 文件`、环境变量 `LT_CONFIG`、`./loadtest.properties`、`./backend/loadtest.properties`。Broker 没配时，使用设备 JSON 里每个设备自带的 `brokerUrl`（含 `<` 的占位地址会被忽略）。

## 4. 运行
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

### 复现 15k msg/s

1. 建 5 000 台设备：`node scripts/loadtest-setup.mjs --base http://<主机>:8088 --broker mqtt://<主机>:1883 --count 5000`
2. 确认后端是 16 会话：`IOT_MQTT_INGEST_CONNECTIONS=16`、`EMQX_MAX_INFLIGHT=4096`，且 `IOT_LOG_OK_PROPERTY=false`。
3. 重启后端，确认 `iot_mqtt_gateway_connections` 达到 16。
4. 跑：

   ```powershell
   .\scripts\loadtest.ps1 --devices backend\devices-5000.local.json --broker mqtt://<主机>:1883 `
     --rate 3 --duration 120 --ramp 100 --report 1 --csv .\lt-15k.csv
   ```

5. 取**同一时间窗口**对比：后端 `iot_ingest_processed_total` 的增量 vs EMQX `messages_sent` 的增量（后端重启会归零，EMQX 不会）。
6. 预期：EMQX 各会话 mqueue 全程 0、总在途峰值远低于 `16 × 4096`；后端处理量等于 `messages_sent`、broker 零丢弃。客户端可能有 2–3% 的长尾 `failed`（stage U 是 36 584 次、ackP95 3.56 s），这是客户端在长尾延迟下打满自己的发送节拍，不是后端欠账；要压到 0 应查 ack 长尾，而不是继续加窗口。
7. 若每个属性都写历史，15k × 7 = 105k 点/s，`IOT_BATCH_HISTORY_WORKERS=2` 会丢点（约 89k/s 上限）；要全量保存就调到 4。只看接入链路时可以直接看 `iot_batch_queue_dropped_total{queue="history"}`。

## 参数（均可写进配置文件）
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

## 要让一万个设备同时在线
一万连接对 EMQX 本身是很轻的负载，卡住通常是别处的限制。每个设备连接一次，EMQX 会回调后端约 3 次（连接认证 1 次 + 首次发布 `status`、`property/post` 两个主题各 1 次授权）。按先后查：

1. **看压测输出里的连接统计**：`pending` = 已发起但没连上的数量，`connFail`/`lost` = 连接失败/丢失次数，下面会打印最多的 3 种原因（`connect problem xN: …`）。原因是 `Connection refused / timed out` 偏向服务端或网络，`Cannot assign requested address / Address already in use` 是**压测机端口用完**，`NOT_AUTHORIZED` 是认证被拒。
2. **压测机端口**：Windows 默认只有 16384 个临时端口（`netsh int ipv4 show dynamicport tcp`），关闭的连接还要 TIME_WAIT 约 2 分钟。连续压测、上一轮刚停，端口就会被占。两轮之间等 2～4 分钟，或（管理员）`netsh int ipv4 set dynamicport tcp start=10000 num=55000` 后重启压测；更大规模用多台压测机。
3. **EMQX 回调**：HTTP 源连接池（你截图 8）调到 32～64，请求超时 5～10 秒，开授权缓存；连接速率 `--ramp` 从 50 起步（一万个需要约 200 秒）。EMQX 日志里 authn/authz 超时说明回调跟不上。
4. **EMQX 所在虚拟机**：`ulimit -n` ≥ 连接数×2、内存 ≥ 4GB（见 [ingest.md](ingest.md) §3）。
5. **对比实验**：在 EMQX 里临时把认证/授权换成内置的（或临时允许匿名，仅限内网测试），再跑一次。能到 10000 说明瓶颈在回调；仍然到不了，说明是 EMQX/系统/网络限制。
6. **看 EMQX Dashboard 的连接数**，和压测输出的 `connected` 对比：一致说明 EMQX 就只收到了这么多；Dashboard 更多说明是压测端统计问题。

## 输出
```
[load] connected=1000/1000 pending=0 sent=1000/s acked=998/s failed=0 ackAvg=4ms ackP95=11ms authFail=0 errors=0 connFail=0 lost=0 reconnects=0
```
- `sent` / `acked`：发出 / 收到 Broker 确认（QoS1 的 PUBACK）的速率。两者长期差距大说明 Broker 或网络吃不下。
- `ackAvg` / `ackP95`：发布到 PUBACK 的耗时，**只到 EMQX 为止**，不含后端处理。后端处理速度看平台“数据总览”的 TPS 卡片或后端日志里的 `[ingest]` 行。
- `authFail`：认证被拒数（密钥不对、设备被禁用或删除、EMQX 回调后端失败等）。`errors`：其他连接错误。
- 结束时打印总发送量、平均速率、峰值连接数。

## 注意
- `id` 为 `设备序号-序号`，不会被平台的消息去重吞掉；每个设备的上报带随机相位，不会所有设备同一毫秒发送。
- 压测会真实写入数据库（历史、最新值；通信记录默认只记失败/事件/状态/指令），结束后注意清理。历史每个样本一行，数据量增长很快；只想看接入能力时可以把 `IOT_HISTORY_STORE` 切到 InfluxDB 或降低 `--rate`。
- 本机文件句柄和线程有限，单进程几千连接以上请用 `--shard` 开多个进程，或多台机器分担。
- 告警规则、离线规则也会生效；压测结束设备断开会触发“设备离线”告警。
