# API 契约（前后端共同遵守）

Base URL：`/api`（开发环境前端通过 Vite proxy 转发到 `http://localhost:8080`）。
JSON，UTF-8，时间一律为 **ISO-8601 UTC 字符串**（如 `2026-01-01T08:00:00Z`），WebSocket 中的 `ts` 为毫秒时间戳。
鉴权：`Authorization: Bearer <JWT>`，除 `POST /api/auth/login` 与 `/actuator/health` 外全部需要。

## 统一响应

```json
{ "code": 0, "message": "ok", "data": <任意> }
```
- 成功 `code=0`。失败 `code != 0`，同时 HTTP 状态码对应：
  - 400 参数错误 (`code=400`，`data` 为 `{字段: 错误信息}`，可为空)
  - 401 未登录/Token 过期 (`code=401`)
  - 403 无权限 (`code=403`)
  - 404 不存在 (`code=404`)
  - 409 业务冲突（如产品下仍有设备、设备离线无法下发）(`code=409`，`message` 为可直接展示的中文提示)
  - 500 内部错误 (`code=500`)
- 分页响应 `data`：`{ "records": [...], "total": 123, "page": 1, "size": 10 }`；请求参数 `page`（从 1 开始）、`size`（最大 100）。

## 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /auth/login | `{username,password}` → `{token, expiresIn(秒), user}` |
| POST | /auth/logout | 退出（服务端记录日志，令牌随后失效） |
| GET | /auth/me | → `user` |
| PUT | /auth/password | `{oldPassword,newPassword}` |

`user`：`{id, username, nickname, tenantId, roles:["ADMIN"], permissions:["product:view", ...]}`

## 权限码

`dashboard:view`，`product:view|create|update|delete`，`thing:view|edit`，`device:view|create|update|delete|credential|control`，
`telemetry:view`，`alarm:view|rule|ack`，`system:user|role|log|config`。
前端路由 `meta.permission` 与按钮显隐使用这些码；后端 `@PreAuthorize` 是最终校验。

## 系统管理（均需对应 `system:*` 权限）

- `GET /system/users?page&size&keyword` → 分页 `{id,username,nickname,email,enabled,roles:[roleCode],createdAt}`
- `POST /system/users` `{username,password,nickname,email,roleIds:[]}`；`PUT /system/users/{id}` `{nickname,email,enabled,roleIds}`；`DELETE /system/users/{id}`；`PUT /system/users/{id}/password` `{newPassword}`
- `GET /system/roles` → `[{id,code,name,description,permissions:[code]}]`；`POST/PUT /system/roles[/{id}]` `{code,name,description,permissions:[code]}`；`DELETE /system/roles/{id}`
- `GET /system/permissions` → 权限树 `[{code,name,type:"MENU"|"API",children:[...]}]`
- `GET /system/logs/operation?page&size&keyword&username` → `{id,username,module,action,method,path,ip,success,costMs,detail,createdAt}`
- `GET /system/logs/login?page&size&username` → `{id,username,ip,success,message,createdAt}`
- `GET /system/configs` → `[{key,value,description}]`；`PUT /system/configs` `{key,value}`
- `GET /actuator/health`（匿名）

## 产品 `/products`

`Product`：`{id,name,productKey,category,protocol("MQTT"),status("ENABLED"|"DISABLED"),description,deviceCount,thingModelVersion,createdAt,updatedAt}`

- `GET /products?page&size&keyword&category&status`
- `GET /products/{id}`；`POST /products` `{name,productKey,category,protocol,status,description}`；`PUT /products/{id}`（productKey 不可改）；`DELETE /products/{id}`（有设备 → 409）
- `GET /products/{id}/devices?page&size` → `Device` 分页
- `GET /products/options` → `[{id,name,productKey}]`（下拉选择用，无分页）

## 物模型

`GET /products/{id}/thing-model` → `{productId, version, properties:[Property], events:[Event], services:[Service], updatedAt}`
`PUT /products/{id}/thing-model` body `{properties,events,services}` → 保存为新版本，返回同上。`GET /products/{id}/thing-model/versions` → `[{version,createdAt,createdBy}]`。

```
Property: {identifier, name, dataType:"INT"|"FLOAT"|"BOOL"|"STRING"|"ENUM"|"JSON", unit, description,
           accessMode:"R"|"RW", spec:{min,max,step,maxLength,enumValues:[{value,label}]}}
Event:    {identifier, name, level:"INFO"|"WARNING"|"ERROR", description, outputs:[{identifier,name,dataType}]}
Service:  {identifier, name, description, inputs:[{identifier,name,dataType,spec}], outputs:[{identifier,name,dataType}]}
```
`identifier`：`^[a-zA-Z][a-zA-Z0-9_]{0,63}$`，同一类别内唯一。ENUM 的 `value` 为字符串/数字均可。

## 设备 `/devices`

`Device`：`{id,name,deviceKey,productId,productName,productKey,description,enabled,onlineStatus:"ONLINE"|"OFFLINE"|"INACTIVE",mqttConnected,clientId,lastOnlineAt,lastOfflineAt,lastCommunicationAt,createdAt}`
（`INACTIVE`=从未接入。**任何 Device 响应都不含密钥**。）

- `GET /devices?page&size&keyword&productId&onlineStatus&enabled`
- `GET /devices/{id}`
- `POST /devices` `{name,productId,deviceKey?(可省略则自动生成),description}` → `Device & {secret}`（secret 仅此一次返回）
- `PUT /devices/{id}` `{name,description}`；`DELETE /devices/{id}`
- `POST /devices/{id}/rotate-secret` → `{secret}`（仅此一次，旧密钥立即失效并踢下线）
- `PUT /devices/{id}/enabled` `{enabled:true|false}`

## 遥测与监控（`telemetry:view`）

- `GET /devices/{id}/latest` → `[{identifier,name,unit,dataType,value,reportedAt}]`（`value` 为 JSON 值；未上报过的属性不返回）
- `GET /devices/{id}/history?identifiers=a,b&start&end&page&size` → 原始数据分页 `{identifier,value,ts}`（ts 为 ISO 字符串，倒序；start/end 必填或默认最近 1 小时；跨度≤7 天）
- `GET /devices/{id}/trend?identifiers=a,b&start&end&interval=RAW|1m|5m|1h|1d` → `{interval, series:[{identifier,name,unit,points:[[tsMillis,value]]}]}`（数值型属性取平均；每条序列最多 2000 点，超出自动放大 interval）
- `GET /devices/{id}/events?page&size&level` → `{id,identifier,name,level,params,ts}`
- `GET /devices/{id}/messages?page&size&direction=UP|DOWN` → 通信记录 `{id,direction,type:"PROPERTY"|"EVENT"|"STATUS"|"COMMAND"|"REPLY",topic,payload,result:"OK"|"INVALID"|"REJECTED",message,createdAt}`

## 控制指令 `device:control`

`Command`：`{id,commandNo,deviceId,type:"SERVICE"|"PROPERTY_SET"|"PROPERTY_GET",identifier,params,status:"PENDING"|"SENT"|"RECEIVED"|"SUCCESS"|"FAILED"|"TIMEOUT",result,message,createdBy,createdAt,sentAt,receivedAt,finishedAt}`

- `POST /devices/{id}/commands` `{type,identifier,params}`：
  - `SERVICE`：identifier = 服务标识，params 按服务 inputs 校验
  - `PROPERTY_SET`：identifier = 可写(RW)属性标识，params `{value: ...}`
  - `PROPERTY_GET`：identifier 可空，params `{identifiers:[...]}`
  - 设备离线/禁用 → 409 并返回明确中文提示；参数不合法 → 400。返回 `Command`（status 通常为 `SENT`）。
- `GET /devices/{id}/commands?page&size&status`；`GET /commands/{id}`
- 状态含义：PENDING 已落库未发出；SENT 已发布到 Broker；RECEIVED 设备确认收到；SUCCESS/FAILED 设备返回执行结果；TIMEOUT 在超时时间（默认 30s）内无终态结果。

## 告警（`alarm:view|rule|ack`）

`AlarmRule`：`{id,name,scope:"PRODUCT"|"DEVICE",productId,deviceId,productName,deviceName,ruleType:"THRESHOLD"|"OFFLINE"|"EVENT",identifier,operator:"GT"|"GTE"|"LT"|"LTE"|"EQ"|"NE",threshold,triggerCount,silenceSeconds,level:"INFO"|"WARNING"|"MAJOR"|"CRITICAL",enabled,description,createdAt}`
- THRESHOLD：identifier=属性，operator+threshold，需**连续** `triggerCount` 次满足才触发。OFFLINE：设备离线触发，上线恢复。EVENT：identifier=事件标识，收到即触发（无自动恢复，需人工处理）。
- `GET /alarm-rules?page&size&keyword&ruleType&enabled`；`POST /alarm-rules`；`PUT /alarm-rules/{id}`；`DELETE /alarm-rules/{id}`；`PUT /alarm-rules/{id}/enabled` `{enabled}`

`Alarm`：`{id,ruleId,ruleName,deviceId,deviceName,ruleType,level,status:"ACTIVE"|"RECOVERED",acknowledged,message,triggerValue,triggerCount,firstTriggeredAt,lastTriggeredAt,recoveredAt,acknowledgedBy,acknowledgedAt}`
- `GET /alarms?page&size&status&acknowledged&level&deviceId&start&end`
- `POST /alarms/{id}/ack` `{remark}`；`POST /alarms/{id}/comments` `{remark}`；`GET /alarms/{id}/actions` → `[{id,action:"TRIGGER"|"ACK"|"COMMENT"|"RECOVER",operator,remark,createdAt}]`
- 同一 (规则, 设备) 在 ACTIVE 期间不重复生成记录，只更新 `lastTriggeredAt/triggerCount`，恢复后再次触发才新建。

## 仪表盘（`dashboard:view`）

- `GET /dashboard/overview` → `{productCount,deviceCount,onlineCount,offlineCount,inactiveCount,onlineRate(0-100,两位小数),todayAlarmCount,activeAlarmCount,todayMessageCount}`
- `GET /dashboard/report-trend?hours=24` → `[{time(ISO,整点),count}]`（每小时属性上报消息数，补全空桶）
- `GET /dashboard/alarm-stats?days=7` → `{byLevel:[{level,count}],byDay:[{date,count}]}`
- `GET /dashboard/recent-devices?limit=10` → 最近上线设备 `Device[]`
- `GET /dashboard/top-devices?limit=5` → 最近上报最活跃设备 `[{deviceId,name,messageCount}]`

## WebSocket

`ws://host/ws?token=<JWT>`（开发环境 Vite 代理 `/ws`）。握手无有效 token 则拒绝。
客户端 → 服务端：
```json
{"action":"subscribe","deviceId":1}     {"action":"unsubscribe","deviceId":1}
{"action":"subscribe","topic":"dashboard"}   {"action":"unsubscribe","topic":"dashboard"}
```
订阅设备时服务端校验租户与 `telemetry:view` 权限，失败返回 `{"type":"error","message":"..."}`；成功返回 `{"type":"subscribed","deviceId":1}`。
服务端 → 客户端（均带 `deviceId`，`ts` 毫秒）：
```json
{"type":"property","deviceId":1,"ts":1700000000000,"data":{"temperature":23.5}}
{"type":"status","deviceId":1,"ts":..,"onlineStatus":"ONLINE"}
{"type":"event","deviceId":1,"ts":..,"identifier":"overheat","level":"WARNING","params":{}}
{"type":"command","deviceId":1,"command":{Command}}
{"type":"alarm","deviceId":1,"alarm":{Alarm}}
{"type":"dashboard"}     // 订阅 dashboard 的客户端收到后应重新拉取统计接口
```
