import { ref, shallowRef, watch, type Ref } from 'vue'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import type { DataType, Spec, ThingModel } from '@/types'

export interface PropInfo {
  identifier: string
  name: string
  unit?: string
  dataType: DataType
  writable: boolean
  spec?: Spec
}

/**
 * 加载设备所属产品的物模型（静默，无 thing:view 权限时回退为 /latest 中出现过的属性）。
 */
export function useThingModel(deviceId: Ref<number>, productId: Ref<number | undefined>) {
  const thingModel = shallowRef<ThingModel | null>(null)
  const props = ref<PropInfo[]>([])
  const loading = ref(false)
  const fromThingModel = ref(false)

  async function load() {
    if (!productId.value) return
    loading.value = true
    try {
      const tm = await productApi.getThingModel(productId.value, true)
      thingModel.value = tm
      fromThingModel.value = true
      props.value = (tm.properties ?? []).map((p) => ({
        identifier: p.identifier,
        name: p.name,
        unit: p.unit,
        dataType: p.dataType,
        writable: p.accessMode === 'RW',
        spec: p.spec,
      }))
    } catch {
      fromThingModel.value = false
      thingModel.value = null
      try {
        const latest = await deviceApi.latest(deviceId.value)
        props.value = latest.map((l) => ({
          identifier: l.identifier,
          name: l.name,
          unit: l.unit,
          dataType: l.dataType,
          writable: false,
        }))
      } catch {
        props.value = []
      }
    } finally {
      loading.value = false
    }
  }

  watch(productId, load, { immediate: true })

  return { thingModel, props, loading, fromThingModel, reload: load }
}
