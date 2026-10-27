<template>
  <el-container class="layout">
    <el-aside v-if="!isNarrow" :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo">{{ collapsed ? 'OH' : 'OmniHub' }}</div>
      <el-scrollbar>
        <SidebarMenu :collapse="collapsed" />
      </el-scrollbar>
    </el-aside>
    <el-drawer
      v-else
      v-model="drawer"
      direction="ltr"
      size="220px"
      :with-header="false"
      class="menu-drawer"
    >
      <div class="logo">OmniHub</div>
      <SidebarMenu @click="drawer = false" />
    </el-drawer>

    <el-container class="main">
      <el-header class="header"><HeaderBar @toggle="toggle" /></el-header>
      <el-main class="content">
        <router-view v-slot="{ Component, route: r }">
          <component :is="Component" :key="r.path" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useBreakpoint } from '@/composables/useBreakpoint'
import HeaderBar from './HeaderBar.vue'
import SidebarMenu from './SidebarMenu.vue'

const { isNarrow } = useBreakpoint(992)
const collapsed = ref(false)
const drawer = ref(false)
const route = useRoute()

function toggle() {
  if (isNarrow.value) drawer.value = !drawer.value
  else collapsed.value = !collapsed.value
}

watch(
  () => route.path,
  () => {
    drawer.value = false
  },
)
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background: #1f2d3d;
  transition: width 0.2s;
  overflow: hidden;
}
.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 1px;
  background: #1f2d3d;
}
.main {
  min-width: 0;
}
.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  height: 56px;
}
.content {
  padding: 16px;
}
</style>

<style>
.menu-drawer {
  background: #1f2d3d !important;
}
.menu-drawer .el-drawer__body {
  padding: 0;
}
</style>
