import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { routes, layoutChildren } from './routes'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/** 找到当前用户能访问的第一个页面（用于首页无权限时的兜底跳转） */
function firstAccessiblePath(
  list: RouteRecordRaw[],
  has: (p?: string) => boolean,
  base = '/',
): string | null {
  for (const r of list) {
    if (r.meta?.hidden || r.path.includes(':')) continue
    const full = `${base.replace(/\/$/, '')}/${r.path}`
    if (r.children?.length) {
      const sub = firstAccessiblePath(r.children, has, full)
      if (sub) return sub
    } else if (has(r.meta?.permission)) {
      return full
    }
  }
  return null
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  document.title = `${to.meta.title ? `${to.meta.title} - ` : ''}OmniHub 物联网平台`

  if (to.meta.public && to.name !== 'login') return true

  if (to.name === 'login') {
    return auth.isLoggedIn ? { path: (to.query.redirect as string) || '/' } : true
  }

  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.reset()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  const perm = to.meta.permission
  if (perm && !auth.hasPermission(perm)) {
    // 默认首页无权限时，尽量跳到第一个可访问页面
    if (to.path === '/dashboard') {
      const first = firstAccessiblePath(layoutChildren, (p) => auth.hasPermission(p))
      if (first && first !== to.path) return first
    }
    return { name: 'forbidden' }
  }
  return true
})

export default router
