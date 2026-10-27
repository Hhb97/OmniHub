<template>
  <div>
    <div class="toolbar bar">
      <el-date-picker
        v-model="range"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :shortcuts="shortcuts"
        :clearable="false"
        style="width: 380px; max-width: 100%"
      />
      <el-select
        v-model="identifiers"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="属性（必选）"
        style="width: 260px"
      >
        <el-option
          v-for="p in properties"
          :key="p.identifier"
          :label="`${p.name || p.identifier} (${p.identifier})`"
          :value="p.identifier"
        />
      </el-select>
      <el-select v-model="interval" style="width: 120px">
        <el-option v-for="o in TREND_INTERVALS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-button type="primary" :loading="trendLoading || loading" @click="query">查询</el-button>
    </div>

    <el-alert
      v-if="trendError"
      type="error"
      :title="trendError"
      show-icon
      :closable="false"
      class="alert"
    />
    <TrendChart
      :series="series"
      :loading="trendLoading"
      :empty-text="queried ? '所选时间段内暂无数据' : '请选择属性与时间范围后查询'"
    />

    <h4>原始数据</h4>
    <el-table v-loading="loading" :data="rows" size="small" stripe>
      <template #empty
        ><TableEmpty :error="error" :text="queried ? '暂无数据' : '请先查询'" @retry="load"
      /></template>
      <el-table-column label="时间" width="190"
        ><template #default="{ row }">{{ formatTime(row.ts) }}</template></el-table-column
      >
      <el-table-column prop="identifier" label="属性" min-width="160" />
      <el-table-column label="值" min-width="200"
        ><template #default="{ row }">{{ stringify(row.value) }}</template></el-table-column
      >
    </el-table>
    <AppPagination
      :page="page"
      :size="size"
      :total="total"
      @page-change="onPageChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import AppPagination from '@/components/AppPagination.vue'
import TableEmpty from '@/components/TableEmpty.vue'
import TrendChart from '@/components/TrendChart.vue'
import { deviceApi } from '@/api/device'
import { useTable } from '@/composables/useTable'
import type { PropInfo } from '@/composables/useThingModel'
import type { Device, HistoryRecord, TrendInterval, TrendSeries } from '@/types'
import { TREND_INTERVALS } from '@/utils/constants'
import { formatTime, stringify, toIso } from '@/utils/format'

const props = defineProps<{ device: Device; properties: PropInfo[] }>()

const hour = 3600_000
const now = () => new Date()
const range = ref<[Date, Date]>([new Date(now().getTime() - hour), now()])
const shortcuts = [
  { text: '最近 1 小时', value: () => [new Date(Date.now() - hour), new Date()] },
  { text: '最近 6 小时', value: () => [new Date(Date.now() - 6 * hour), new Date()] },
  { text: '最近 24 小时', value: () => [new Date(Date.now() - 24 * hour), new Date()] },
  { text: '最近 7 天', value: () => [new Date(Date.now() - 7 * 24 * hour), new Date()] },
]
const identifiers = ref<string[]>([])
const interval = ref<TrendInterval>('5m')
const queried = ref(false)

const series = shallowRef<TrendSeries[]>([])
const trendLoading = ref(false)
const trendError = ref('')

const { rows, total, page, size, loading, error, load, onPageChange, onSizeChange } = useTable<
  HistoryRecord,
  object
>(
  (p) =>
    deviceApi.history(props.device.id, {
      page: p.page,
      size: p.size,
      identifiers: identifiers.value.join(','),
      start: toIso(range.value[0]),
      end: toIso(range.value[1]),
    }),
  {},
  { immediate: false },
)

const valid = computed(() => identifiers.value.length > 0)

async function query() {
  if (!valid.value) {
    ElMessage.warning('请至少选择一个属性')
    return
  }
  const [s, e] = range.value
  if (e.getTime() - s.getTime() > 7 * 24 * hour) {
    ElMessage.warning('时间跨度不能超过 7 天')
    return
  }
  if (s >= e) {
    ElMessage.warning('开始时间必须早于结束时间')
    return
  }
  queried.value = true
  page.value = 1
  void load()
  trendLoading.value = true
  trendError.value = ''
  try {
    const res = await deviceApi.trend(props.device.id, {
      identifiers: identifiers.value.join(','),
      start: toIso(s),
      end: toIso(e),
      interval: interval.value,
    })
    series.value = res.series
  } catch (err) {
    trendError.value = err instanceof Error ? err.message : '加载失败'
    series.value = []
  } finally {
    trendLoading.value = false
  }
}
</script>

<style scoped>
.bar {
  margin-bottom: 12px;
}
.alert {
  margin-bottom: 12px;
}
h4 {
  margin: 20px 0 8px;
}
</style>
