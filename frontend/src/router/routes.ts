import type { RouteRecordRaw } from 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** Element Plus 图标组件名 */
    icon?: string
    /** 访问所需权限码 */
    permission?: string
    /** 不在侧边栏显示 */
    hidden?: boolean
    /** 无需登录 */
    public?: boolean
    /** 面包屑中的上级 */
    parent?: { title: string; path: string }
  }
}

/** 布局内的业务路由，侧边栏菜单由它生成 */
export const layoutChildren: RouteRecordRaw[] = [
  {
    path: 'dashboard',
    name: 'dashboard',
    component: () => import('@/views/dashboard/DashboardView.vue'),
    meta: { title: '数据概览', icon: 'DataBoard', permission: 'dashboard:view' },
  },
  {
    path: 'products',
    name: 'products',
    component: () => import('@/views/product/ProductsView.vue'),
    meta: { title: '产品管理', icon: 'Box', permission: 'product:view' },
  },
  {
    path: 'products/:id/thing-model',
    name: 'thing-model',
    component: () => import('@/views/thingmodel/ThingModelView.vue'),
    meta: {
      title: '物模型',
      permission: 'thing:view',
      hidden: true,
      parent: { title: '产品管理', path: '/products' },
    },
  },
  {
    path: 'products/:id/devices',
    name: 'product-devices',
    component: () => import('@/views/product/ProductDevicesView.vue'),
    meta: {
      title: '产品设备',
      permission: 'product:view',
      hidden: true,
      parent: { title: '产品管理', path: '/products' },
    },
  },
  {
    path: 'devices',
    name: 'devices',
    component: () => import('@/views/device/DevicesView.vue'),
    meta: { title: '设备管理', icon: 'Cpu', permission: 'device:view' },
  },
  {
    path: 'devices/:id',
    name: 'device-detail',
    component: () => import('@/views/device/DeviceDetailView.vue'),
    meta: {
      title: '设备详情',
      permission: 'device:view',
      hidden: true,
      parent: { title: '设备管理', path: '/devices' },
    },
  },
  {
    path: 'alarm-rules',
    name: 'alarm-rules',
    component: () => import('@/views/alarm/AlarmRulesView.vue'),
    meta: { title: '告警规则', icon: 'SetUp', permission: 'alarm:rule' },
  },
  {
    path: 'alarms',
    name: 'alarms',
    component: () => import('@/views/alarm/AlarmsView.vue'),
    meta: { title: '告警记录', icon: 'Bell', permission: 'alarm:view' },
  },
  {
    path: 'system',
    name: 'system',
    redirect: '/system/users',
    meta: { title: '系统管理', icon: 'Setting' },
    children: [
      {
        path: 'users',
        name: 'system-users',
        component: () => import('@/views/system/UsersView.vue'),
        meta: { title: '用户管理', permission: 'system:user' },
      },
      {
        path: 'roles',
        name: 'system-roles',
        component: () => import('@/views/system/RolesView.vue'),
        meta: { title: '角色管理', permission: 'system:role' },
      },
      {
        path: 'operation-logs',
        name: 'system-operation-logs',
        component: () => import('@/views/system/OperationLogsView.vue'),
        meta: { title: '操作日志', permission: 'system:log' },
      },
      {
        path: 'login-logs',
        name: 'system-login-logs',
        component: () => import('@/views/system/LoginLogsView.vue'),
        meta: { title: '登录日志', permission: 'system:log' },
      },
      {
        path: 'configs',
        name: 'system-configs',
        component: () => import('@/views/system/ConfigsView.vue'),
        meta: { title: '系统配置', permission: 'system:config' },
      },
    ],
  },
]

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    name: 'root',
    component: () => import('@/layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      ...layoutChildren,
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/error/ForbiddenView.vue'),
        meta: { title: '无权限', hidden: true },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]
