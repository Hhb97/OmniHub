<template>
  <div class="page">
    <el-alert v-if="overviewError" type="error" :title="overviewError" show-icon :closable="false">
      <template #default>
        <el-button link type="primary" @click="reload">重试</el-button>
      </template>
    </el-alert>
    <StatCards :overview="overview" :loading="loading" />
    <el-row :gutter="16">
      <el-col :xs="24" :lg="8" class="col">
        <OnlineRateCard :overview="overview" :loading="loading" />
      </el-col>
      <el-col :xs="24" :lg="16" class="col">
        <ReportTrendCard :refresh-key="refreshKey" />
      </el-col>
    </el-row>
    <AlarmStatsCard :refresh-key="refreshKey" />
    <el-row :gutter="16">
      <el-col :xs="24" :lg="16" class="col">
        <RecentDevicesCard :refresh-key="refreshKey" />
      </el-col>
      <el-col :xs="24" :lg="8" class="col">
        <TopDevicesCard :refresh-key="refreshKey" />
      </el-col>
    </el-row>
    <DeviceTrendCard />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { dashboardApi } from '@/api/dashboard'
import { useAsyncData } from '@/composables/useAsyncData'
import { useWsSubscription } from '@/composables/useWsSubscription'
import { debounce } from '@/utils/debounce'
import StatCards from './StatCards.vue'
import OnlineRateCard from './OnlineRateCard.vue'
import ReportTrendCard from './ReportTrendCard.vue'
import AlarmStatsCard from './AlarmStatsCard.vue'
import RecentDevicesCard from './RecentDevicesCard.vue'
import TopDevicesCard from './TopDevicesCard.vue'
import DeviceTrendCard from './DeviceTrendCard.vue'

const {
  data: overview,
  loading,
  error: overviewError,
  reload,
} = useAsyncData(() => dashboardApi.overview())

/** 每次自增，子卡片监听后静默重新拉取 */
const refreshKey = ref(0)

const onDashboardEvent = debounce(() => {
  void reload()
  refreshKey.value++
}, 1000)

useWsSubscription({
  topic: 'dashboard',
  onMessage: (msg) => {
    if (msg.type === 'dashboard') onDashboardEvent()
  },
})
</script>

<style scoped>
.col {
  margin-bottom: 0;
}
@media (max-width: 1199px) {
  .col {
    margin-bottom: 16px;
  }
}
</style>
