<template>
  <div class="header-bar">
    <el-icon class="toggle" :size="20" @click="emit('toggle')"><Fold /></el-icon>
    <el-breadcrumb separator="/" class="crumb">
      <el-breadcrumb-item v-for="c in crumbs" :key="c.title" :to="c.path">
        {{ c.title }}
      </el-breadcrumb-item>
    </el-breadcrumb>
    <div class="spacer" />
    <span class="ws" :title="wsText">
      <i class="dot" :class="wsStatus" />
      <span class="ws-text">{{ wsText }}</span>
    </span>
    <el-dropdown trigger="click" @command="onCommand">
      <span class="user">
        <el-avatar :size="28">{{ initial }}</el-avatar>
        <span class="name">{{ auth.user?.nickname || auth.user?.username }}</span>
        <el-icon><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="password">修改密码</el-dropdown-item>
          <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <ChangePasswordDialog v-model="pwdVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Fold } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { wsStatus } from '@/utils/ws'
import ChangePasswordDialog from './ChangePasswordDialog.vue'

const emit = defineEmits<{ (e: 'toggle'): void }>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const pwdVisible = ref(false)

const initial = computed(() => (auth.user?.nickname || auth.user?.username || '?').slice(0, 1))

const crumbs = computed(() => {
  const list: { title: string; path?: string }[] = []
  const parent = route.meta.parent
  if (parent) list.push({ title: parent.title, path: parent.path })
  for (const m of route.matched) {
    if (m.meta.title) list.push({ title: m.meta.title })
  }
  return list
})

const wsText = computed(
  () =>
    ({
      idle: '实时通道未启用',
      connecting: '实时通道连接中',
      open: '实时通道已连接',
      reconnecting: '实时通道重连中',
    })[wsStatus.value],
)

async function onCommand(cmd: string) {
  if (cmd === 'password') {
    pwdVisible.value = true
  } else if (cmd === 'logout') {
    await auth.logout()
    await router.replace('/login')
  }
}
</script>

<style scoped>
.header-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 100%;
}
.toggle {
  cursor: pointer;
}
.spacer {
  flex: 1;
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.ws {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
}
.dot.open {
  background: #67c23a;
}
.dot.connecting,
.dot.reconnecting {
  background: #e6a23c;
}
@media (max-width: 640px) {
  .name,
  .ws-text {
    display: none;
  }
}
</style>
