import { onMounted, ref, shallowRef, type Ref, type ShallowRef } from 'vue'

export interface UseAsyncData<T> {
  data: ShallowRef<T | null>
  loading: Ref<boolean>
  error: Ref<string>
  reload: () => Promise<void>
}

/**
 * 通用单次请求状态。已有数据时再次 reload 不显示 loading（静默刷新）。
 */
export function useAsyncData<T>(fetcher: () => Promise<T>, immediate = true): UseAsyncData<T> {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref('')
  let seq = 0

  async function reload() {
    const my = ++seq
    if (data.value === null) loading.value = true
    try {
      const res = await fetcher()
      if (my !== seq) return
      data.value = res
      error.value = ''
    } catch (e) {
      if (my !== seq) return
      error.value = e instanceof Error ? e.message : '加载失败'
    } finally {
      if (my === seq) loading.value = false
    }
  }

  if (immediate) onMounted(() => void reload())

  return { data, loading, error, reload }
}
