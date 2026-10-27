import { onMounted, ref } from 'vue'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import type { Device, ProductOption } from '@/types'

/** 产品下拉选项 */
export function useProductOptions(immediate = true) {
  const options = ref<ProductOption[]>([])
  const loading = ref(false)
  async function load() {
    loading.value = true
    try {
      options.value = await productApi.options()
    } catch {
      options.value = []
    } finally {
      loading.value = false
    }
  }
  if (immediate) onMounted(() => void load())
  return { options, loading, load }
}

/** 设备远程搜索下拉（后端无设备 options 接口，使用分页列表接口 size=20） */
export function useDeviceSearch() {
  const options = ref<Device[]>([])
  const loading = ref(false)
  let seq = 0
  async function search(keyword = '', productId?: number) {
    const my = ++seq
    loading.value = true
    try {
      const res = await deviceApi.list({ page: 1, size: 20, keyword, productId })
      if (my === seq) options.value = res.records
    } catch {
      if (my === seq) options.value = []
    } finally {
      if (my === seq) loading.value = false
    }
  }
  /** 确保某个已选设备出现在选项中（如编辑回显） */
  function ensure(device: Device) {
    if (!options.value.some((d) => d.id === device.id)) options.value.unshift(device)
  }
  return { options, loading, search, ensure }
}
