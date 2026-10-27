<template>
  <el-menu
    :default-active="active"
    :collapse="collapse"
    :collapse-transition="false"
    background-color="#1f2d3d"
    text-color="#bfcbd9"
    active-text-color="#409eff"
    router
    class="sidebar-menu"
  >
    <template v-for="item in items" :key="item.path">
      <el-sub-menu v-if="item.children?.length" :index="item.path">
        <template #title>
          <el-icon><component :is="menuIcons[item.icon ?? ''] ?? menuIcons.Menu" /></el-icon>
          <span>{{ item.title }}</span>
        </template>
        <el-menu-item v-for="c in item.children" :key="c.path" :index="c.path">
          {{ c.title }}
        </el-menu-item>
      </el-sub-menu>
      <el-menu-item v-else :index="item.path">
        <el-icon><component :is="menuIcons[item.icon ?? ''] ?? menuIcons.Menu" /></el-icon>
        <template #title>{{ item.title }}</template>
      </el-menu-item>
    </template>
  </el-menu>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, type RouteRecordRaw } from 'vue-router'
import { layoutChildren } from '@/router/routes'
import { useAuthStore } from '@/stores/auth'
import { menuIcons } from './icons'

defineProps<{ collapse?: boolean }>()

interface MenuItem {
  path: string
  title: string
  icon?: string
  children?: MenuItem[]
}

const route = useRoute()
const auth = useAuthStore()

function build(list: RouteRecordRaw[], base: string): MenuItem[] {
  const out: MenuItem[] = []
  for (const r of list) {
    if (r.meta?.hidden) continue
    const path = `${base.replace(/\/$/, '')}/${r.path}`
    if (r.children?.length) {
      const children = build(r.children, path)
      if (children.length)
        out.push({ path, title: r.meta?.title ?? '', icon: r.meta?.icon, children })
    } else if (auth.hasPermission(r.meta?.permission)) {
      out.push({ path, title: r.meta?.title ?? '', icon: r.meta?.icon })
    }
  }
  return out
}

const items = computed(() => build(layoutChildren, '/'))

/** 详情页高亮其上级菜单 */
const active = computed(() => route.meta.parent?.path ?? route.path)
</script>

<style scoped>
.sidebar-menu {
  border-right: none;
}
</style>
