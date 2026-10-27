# 设备接入协议（MQTT 3.1.1，JSON）

## 连接
| 项 | 值 |
|---|---|
| clientId | `{productKey}.{deviceKey}` |
| username | 与 clientId 相同 |
| password | 创建设备/轮换密钥时**仅返回一次**的设备密钥 |
| 遗嘱 (Will) | topic `iot/{pk}/{dk}/status`，payload `{"state":"offline"}`，QoS1 |
| 建议 | keepalive 30s；连接成功后发布 `{"state":"online"}`；周期上报属性（兼作心跳） |

认证失败（密钥错、设备禁用/删除、产品停用、clientId 与 username 不一致）连接被拒绝。轮换密钥或禁用设备会使平台尝试断开现有连接（仅内置 Broker 实现了强制断开）。

## Topic（均 QoS1）
| 方向 | Topic |
|---|---|
| 上行 | `iot/{pk}/{dk}/property/post` 属性上报 |
| 上行 | `iot/{pk}/{dk}/event/post` 事件上报 |
| 上行 | `iot/{pk}/{dk}/status` 上下线状态 |
| 上行 | `iot/{pk}/{dk}/command/reply` 指令应答 |
| 下行 | `iot/{pk}/{dk}/command/down` 指令下发（设备订阅） |

设备只允许发布自己的上行 Topic、只允许订阅自己的 `command/down`，其他一律拒绝。平台账号 `platform` 不受限。消息体最大 16KB，必须是 JSON 对象。

## 上行消息
**属性** `property/post`
```json
{"id":"1700000000000-1","ts":1700000000000,"params":{"temperature":23.5,"switch":true}}
```
- `id`（可选）：同一设备相同 `id` 的消息只处理一次（内存去重，重启后失效）。
- `ts`（可选，毫秒）：仅当在“过去 7 天～未来 5 分钟”内才采用，否则用服务器时间。
- 每个属性按物模型校验类型/范围/枚举；未定义或非法的属性被拒绝（通信记录标 `INVALID` 并写明原因），同一条消息里其余有效属性仍保存。

**事件** `event/post`：`{"id":"e1","ts":...,"event":"overheat","params":{"temperature":85}}`，`event` 必须在物模型中定义。

**状态** `status`：`{"state":"online"}` / `{"state":"offline"}`。

**指令应答** `command/reply`
```json
{"cmdId":"cmd…","status":"RECEIVED"}
{"cmdId":"cmd…","status":"SUCCESS","message":"ok","data":{"switch":true}}
{"cmdId":"cmd…","status":"FAILED","message":"reason"}
```
`status` 取 `RECEIVED` / `SUCCESS` / `FAILED`；`data` 保存为指令结果。终态之后的重复或迟到回复会被忽略（通信记录标 `REJECTED`）。

## 下行消息
```json
{"cmdId":"cmd1a2b…","type":"SERVICE|PROPERTY_SET|PROPERTY_GET","identifier":"setTargetTemp","params":{"target":26},"ts":1700000000000}
```
- `SERVICE`：`identifier` 为服务标识，`params` 为服务入参。
- `PROPERTY_SET`：`identifier` 为可写属性，`params.value` 为新值。
- `PROPERTY_GET`：`params.identifiers` 为属性数组，设备应回复数据并上报这些属性。

## 状态含义
`PENDING` 已落库未发布 → `SENT` 已发布到 Broker（不代表设备收到）→ `RECEIVED` 设备确认收到 → `SUCCESS`/`FAILED` 设备返回结果；`TIMEOUT`：超过 `command.timeout.seconds`（默认 30）无终态。设备离线或禁用时下发直接返回 409。

## 外部 Broker（EMQX）
`docker-compose.yml` 通过环境变量把 EMQX 的 HTTP 认证与授权指向后端 `/api/mqtt/auth`、`/api/mqtt/acl`（请求头 `X-Broker-Token` = `IOT_MQTT_HTTP_AUTH_TOKEN`），返回 `{"result":"allow|deny"}`。**该配置未在真实 EMQX 上验证**；EMQX 版本不同，配置项名称可能需要调整。
