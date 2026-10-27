import type { DataType, Spec } from '@/types'

/** 按数据类型裁剪 spec，仅保留相关字段；ENUM 值若是纯数字字符串则转为数字 */
export function cleanSpec(dataType: DataType, spec?: Spec): Spec {
  const s = spec ?? {}
  if (dataType === 'INT' || dataType === 'FLOAT') {
    const out: Spec = {}
    if (isNum(s.min)) out.min = s.min
    if (isNum(s.max)) out.max = s.max
    if (isNum(s.step)) out.step = s.step
    return out
  }
  if (dataType === 'STRING') {
    return isNum(s.maxLength) ? { maxLength: s.maxLength } : {}
  }
  if (dataType === 'ENUM') {
    return {
      enumValues: (s.enumValues ?? []).map((e) => ({
        value: normalizeEnumValue(e.value),
        label: e.label,
      })),
    }
  }
  return {}
}

function isNum(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v)
}

export function normalizeEnumValue(v: string | number): string | number {
  if (typeof v === 'number') return v
  const t = v.trim()
  return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : t
}

export function cloneDeep<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
}
