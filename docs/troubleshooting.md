# 故障排查

| 现象 | 原因与处理 |
|---|---|
| 后端启动报 `Port 8080 was already in use` | 端口被占用。`SERVER_PORT=8081` 重新启动，前端用 `VITE_DEV_BACKEND=http://localhost:8081`。 |
| 启动报 `Gave up waiting for server to start` (嵌入式 PG) | 上次后端被强制结束，残留 `postgres.exe` 占着 15432。结束这些进程（路径含 `embedded-pg`），必要时删除 `backend/data/pgdata/postmaster.pid`，再启动。 |
| 启动报 `iot.security.jwt-secret ... must be set` | 非 `local` profile 必须设置 `IOT_JWT_SECRET`（≥32 字符）。 |
| 登录不了 / 没有管理员 | 管理员只在用户表为空时按 `IOT_ADMIN_PASSWORD`（≥8 位）创建；日志出现 “no administrator was created” 说明没设。已有用户时修改该变量无效，请用用户管理重置密码。 |
| 登录返回 429 | 同一用户名 5 分钟内失败 5 次被临时锁定，等待 5 分钟（后端重启也会清除）。 |
| 设备连不上 Broker (not authorized) | 检查 clientId = username = `productKey.deviceKey`、密钥是否最新（轮换后旧密钥失效）、设备/产品是否启用。密钥丢失只能轮换。 |
| 连上了但页面显示离线 | 业务在线要求超时时间内有上行消息。先发 `status` online 并周期上报；检查 `device.offline.timeout.seconds`。 |
| 数据没入库 | 看设备“通信记录”：`INVALID` 行会写明原因（未定义属性、超范围、JSON 错误、超 16KB）。确认物模型已保存。 |
| 指令返回 409 | 设备离线/禁用。其他 409 为产品下有设备等业务冲突，消息直接展示原因。 |
| 指令一直 TIMEOUT | 设备没订阅 `command/down` 或没回复；超时由 `command.timeout.seconds` 控制。 |
| 前端 WebSocket 一直断开 | 开发时 `/ws` 需走 Vite 代理；跨域部署需设置 `IOT_CORS_ORIGINS` 包含前端源；Nginx 需 `Upgrade` 头（见 `deploy/nginx.conf`）。 |
| 前端 401 循环跳登录 | Token 过期或被退出；重新登录。 |
| 仪表盘“今日”不对 | 调整 `IOT_DASHBOARD_ZONE`（默认 UTC）。 |
| Docker 下设备连不上 EMQX | EMQX 认证配置未经验证：查看 EMQX 日志，确认 `backend:8080/api/mqtt/auth` 可达且 `X-Broker-Token` 与 `IOT_MQTT_HTTP_AUTH_TOKEN` 一致；必要时在 EMQX Dashboard 手动配置 HTTP 认证/授权。 |
| 模拟器连不上 | `simulator/config.local.json` 由 `npm run provision` 生成；重新 provision 会轮换密钥，旧模拟器需重启。 |
