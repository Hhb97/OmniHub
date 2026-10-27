<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-button @click="router.push('/products')">返回</el-button>
        <span v-if="product">
          产品：<b>{{ product.name }}</b>
          <span class="muted mono">（{{ product.productKey }}）</span>
        </span>
        <el-tag v-if="model" size="small">当前版本 v{{ model.version }}</el-tag>
        <el-tag v-if="dirty" type="warning" size="small">有未保存的修改</el-tag>
        <div class="spacer" />
        <el-button @click="openVersions">版本历史</el-button>
        <el-button v-if="canEdit" :disabled="!dirty" @click="discard">放弃修改</el-button>
        <el-button v-if="canEdit" type="primary" :loading="saving" :disabled="!dirty" @click="save">
          保存为新版本
        </el-button>
      </div>
    </el-card>

    <el-card v-loading="loading" shadow="never">
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="alert">
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>
      <el-tabs v-if="draft" v-model="tab">
        <el-tab-pane :label="`属性 (${draft.properties.length})`" name="properties">
          <PropertyTab v-model="draft.properties" :editable="canEdit" />
        </el-tab-pane>
        <el-tab-pane :label="`事件 (${draft.events.length})`" name="events">
          <EventTab v-model="draft.events" :editable="canEdit" />
        </el-tab-pane>
        <el-tab-pane :label="`服务 (${draft.services.length})`" name="services">
          <ServiceTab v-model="draft.services" :editable="canEdit" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-drawer v-model="versionsVisible" title="版本历史" size="420px">
      <el-table v-loading="versionsLoading" :data="versions" size="small">
        <template #empty><TableEmpty :error="versionsError" @retry="loadVersions" /></template>
        <el-table-column label="版本" width="90"
          ><template #default="{ row }">v{{ row.version }}</template></el-table-column
        >
        <el-table-column label="创建时间" min-width="160"
          ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column prop="createdBy" label="创建人" width="110" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import TableEmpty from '@/components/TableEmpty.vue'
import { productApi } from '@/api/product'
import { useAuthStore } from '@/stores/auth'
import type { Product, ThingModel, ThingModelBody, ThingModelVersion } from '@/types'
import { formatTime } from '@/utils/format'
import EventTab from './EventTab.vue'
import PropertyTab from './PropertyTab.vue'
import ServiceTab from './ServiceTab.vue'
import { cloneDeep } from './specUtil'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const productId = computed(() => Number(route.params.id))
const canEdit = computed(() => auth.hasPermission('thing:edit'))

const product = ref<Product | null>(null)
const model = ref<ThingModel | null>(null)
const draft = ref<ThingModelBody | null>(null)
const snapshot = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const tab = ref('properties')

const bodyOf = (m: ThingModelBody): ThingModelBody => ({
  properties: m.properties ?? [],
  events: m.events ?? [],
  services: m.services ?? [],
})

const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== snapshot.value)

function applyModel(m: ThingModel) {
  model.value = m
  draft.value = cloneDeep(bodyOf(m))
  snapshot.value = JSON.stringify(draft.value)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [p, m] = await Promise.all([
      productApi.get(productId.value),
      productApi.getThingModel(productId.value),
    ])
    product.value = p
    applyModel(m)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function discard() {
  try {
    await ElMessageBox.confirm('确定放弃所有未保存的修改吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  if (model.value) applyModel(model.value)
}

async function save() {
  if (!draft.value) return
  saving.value = true
  try {
    const saved = await productApi.saveThingModel(productId.value, cloneDeep(draft.value))
    applyModel(saved)
    ElMessage.success(`已保存为新版本 v${saved.version}`)
  } catch {
    /* 已提示 */
  } finally {
    saving.value = false
  }
}

// 版本历史
const versionsVisible = ref(false)
const versions = ref<ThingModelVersion[]>([])
const versionsLoading = ref(false)
const versionsError = ref('')

async function loadVersions() {
  versionsLoading.value = true
  versionsError.value = ''
  try {
    versions.value = await productApi.thingModelVersions(productId.value)
  } catch (e) {
    versionsError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    versionsLoading.value = false
  }
}
function openVersions() {
  versionsVisible.value = true
  void loadVersions()
}

onMounted(load)
watch(productId, load)

// 离开前提示未保存修改
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('有未保存的修改，确定离开吗？', '提示', { type: 'warning' })
    return true
  } catch {
    return false
  }
})
const beforeUnload = (e: BeforeUnloadEvent) => {
  if (dirty.value) e.preventDefault()
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<style scoped>
.alert {
  margin-bottom: 12px;
}
</style>
