# OmniHub 前端

Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios + ECharts。

## 运行

```bash
npm install
cp .env.example .env     # 可选，按需修改
npm run dev              # http://localhost:5173
```

开发服务器代理（见 `vite.config.ts`）：

- `/api` -> `http://localhost:8080`
- `/ws`  -> `ws://localhost:8080`（WebSocket）

后端地址通过 `VITE_DEV_BACKEND` 配置，业务代码只使用相对路径 `/api`、`/ws`。

## 脚本

| 命令 | 说明 |
|---|---|
| `npm run dev` | 开发服务器（5173） |
| `npm run build` | `vue-tsc` 类型检查 + `vite build` |
| `npm run lint` / `lint:fix` | ESLint |
| `npm run format` | Prettier |

## 目录

```
src/
  api/          按业务域拆分的 Axios API 模块（request.ts 为统一封装）
  types/        契约实体的 TypeScript 类型
  stores/       Pinia（auth）
  router/       路由 + 守卫（登录、meta.permission）
  directives/   v-permission
  composables/  useTable（分页）、useAsyncData、useWsSubscription、useThingModel ...
  components/   AppChart、AppPagination、TrendChart、ValueInput ...
  utils/ws.ts   全局唯一 WebSocket 管理器（引用计数订阅、自动重连、重连后重订阅）
  layout/       侧边栏 + 顶栏 + 面包屑
  views/        各页面（按模块分目录）
```

## 说明

- 权限：路由 `meta.permission`、`v-permission="'product:create'"`、`useAuthStore().hasPermission()`。
- 设备密钥只在创建 / 重置后的对话框中展示一次。
- 设备接入信息中的 MQTT Topic 为约定模板（契约未定义），见 `src/utils/mqtt.ts`；Broker 地址可用 `VITE_MQTT_BROKER` 配置。
- 本前端按 `docs/api-contract.md` 编写，未经真实后端联调。
