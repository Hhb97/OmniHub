import { onMounted, reactive, ref, type Ref } from 'vue'
import type { PageQuery, PageResult } from '@/types'

export interface UseTableOptions {
  /** 默认每页条数 */
  pageSize?: number
  /** 挂载后立即加载，默认 true */
  immediate?: boolean
}

export interface UseTable<T, Q extends object> {
  query: Q
  rows: Ref<T[]>
  total: Ref<number>
  page: Ref<number>
  size: Ref<number>
  loading: Ref<boolean>
  error: Ref<string>
  load: () => Promise<void>
  /** 重置到第一页并查询 */
  search: () => Promise<void>
  /** 恢复筛选条件为初始值并查询 */
  reset: () => Promise<void>
  onPageChange: (p: number) => void
  onSizeChange: (s: number) => void
}

/**
 * 分页列表通用逻辑：筛选条件 + 分页 + loading/error + 过期响应丢弃。
 * silent 刷新（如 WebSocket 触发）使用 load(true) 不显示 loading。
 */
export function useTable<T, Q extends object>(
  fetcher: (params: PageQuery & Q) => Promise<PageResult<T>>,
  initialQuery: Q,
  options: UseTableOptions = {},
): UseTable<T, Q> & { refresh: () => Promise<void> } {
  const initial = { ...initialQuery }
  const query = reactive({ ...initialQuery }) as Q
  const rows = ref([]) as Ref<T[]>
  const total = ref(0)
  const page = ref(1)
  const size = ref(options.pageSize ?? 10)
  const loading = ref(false)
  const error = ref('')
  let seq = 0

  async function doLoad(silent: boolean): Promise<void> {
    const my = ++seq
    if (!silent) loading.value = true
    try {
      const res = await fetcher({ ...query, page: page.value, size: size.value } as PageQuery & Q)
      if (my !== seq) return
      rows.value = res.records ?? []
      total.value = res.total ?? 0
      error.value = ''
      // 删除最后一页最后一条后，回退到上一页
      if (rows.value.length === 0 && total.value > 0 && page.value > 1) {
        page.value = Math.max(1, Math.ceil(total.value / size.value))
        await doLoad(silent)
        return
      }
    } catch (e) {
      if (my !== seq) return
      error.value = e instanceof Error ? e.message : '加载失败'
      if (!silent) {
        rows.value = []
        total.value = 0
      }
    } finally {
      if (my === seq) loading.value = false
    }
  }

  const load = () => doLoad(false)
  const refresh = () => doLoad(true)

  async function search() {
    page.value = 1
    await load()
  }

  async function reset() {
    Object.assign(query, initial)
    await search()
  }

  function onPageChange(p: number) {
    page.value = p
    void load()
  }

  function onSizeChange(s: number) {
    size.value = s
    page.value = 1
    void load()
  }

  if (options.immediate !== false) onMounted(() => void load())

  return {
    query,
    rows,
    total,
    page,
    size,
    loading,
    error,
    load,
    refresh,
    search,
    reset,
    onPageChange,
    onSizeChange,
  }
}
