<template>
  <el-card shadow="never">
    <template #header>
      <div class="head">
        <span class="card-title">消息吞吐（TPS）</span>
        <div>
          <el-tag v-if="data" :type="data.mode === 'kafka' ? 'warning' : 'info'" size="small">
            {{ data.mode === 'kafka' ? '经 Kafka' : '直连' }}
          </el-tag>
          <span class="muted tip">每 2 秒刷新，统计最近 60 秒</span>
        </div>
      </div>
    </template>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <el-row :gutter="12" class="metrics">
      <el-col :xs="12" :sm="6">
        <div class="m">
          <div class="v big">{{ data ? data.tps : '-' }}</div>
          <div class="l">当前 TPS（近 5 秒平均，条/秒）</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="m">
          <div class="v">{{ data ? data.peakTps : '-' }}</div>
          <div class="l">60 秒内峰值（条/秒）</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="m">
          <div class="v">{{ data ? `${data.avgDelayMs} / ${data.maxDelayMs}` : '-' }}</div>
          <div class="l">排队延迟 平均 / 最大（ms）</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="m">
          <div class="v" :class="{ danger: (data?.droppedTotal ?? 0) > 0 }">
            {{ data ? `${data.processedTotal} / ${data.droppedTotal}` : '-' }}
          </div>
          <div class="l">累计处理 / 转发丢弃（自启动）</div>
        </div>
      </el-col>
    </el-row>
    <AppChart :option="option" height="220px" :loading="loading" :empty="false" />
    <p class="muted note">
      TPS 为进入处理器的上行消息数（含属性、事件、状态、指令应答）；延迟为收到 MQTT
      消息到开始处理的时间，不含入库耗时。
    </p>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppChart from '@/components/AppChart.vue'
import { dashboardApi } from '@/api/dashboard'
import type { IngestStats } from '@/types'

const data = ref<IngestStats | null>(null)
const loading = ref(true)
const error = ref('')
let timer: number | undefined

async function poll() {
  try {
    data.value = await dashboardApi.ingestStats()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void poll()
  timer = window.setInterval(() => {
    if (!document.hidden) void poll()
  }, 2000)
})
onBeforeUnmount(() => window.clearInterval(timer))

const fmt = (ts: number) => new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })

const option = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 20, bottom: 30, containLabel: true },
  xAxis: {
    type: 'category',
    data: (data.value?.series ?? []).map((s) => fmt(s.ts)),
    boundaryGap: false,
  },
  yAxis: { type: 'value', minInterval: 1, name: '条/秒' },
  series: [
    {
      name: 'TPS',
      type: 'line',
      smooth: true,
      showSymbol: false,
      areaStyle: { opacity: 0.15 },
      itemStyle: { color: '#67c23a' },
      data: (data.value?.series ?? []).map((s) => s.count),
    },
  ],
}))
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.tip {
  margin-left: 8px;
  font-size: 12px;
}
.metrics {
  margin-bottom: 8px;
}
.m {
  padding: 4px 0 8px;
}
.v {
  font-size: 22px;
  font-weight: 600;
}
.v.big {
  font-size: 30px;
  color: #67c23a;
}
.v.danger {
  color: #f56c6c;
}
.l {
  font-size: 12px;
  color: #909399;
}
.note {
  margin: 8px 0 0;
  font-size: 12px;
}
</style>
