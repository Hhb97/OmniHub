import dayjs from 'dayjs'

export function formatTime(value?: string | number | null): string {
  if (value === undefined || value === null || value === '') return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : String(value)
}

export function formatDate(value?: string | number | null): string {
  if (value === undefined || value === null || value === '') return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD') : String(value)
}

export function formatHour(value: string | number): string {
  const d = dayjs(value)
  return d.isValid() ? d.format('MM-DD HH:mm') : String(value)
}

/** 任意值转可读字符串（对象 -> JSON） */
export function stringify(value: unknown): string {
  if (value === undefined || value === null) return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

export function prettyJson(value: unknown): string {
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2) ?? ''
  } catch {
    return String(value)
  }
}

export function formatNumber(n: number | undefined | null): string {
  if (n === undefined || n === null) return '-'
  return n.toLocaleString('zh-CN')
}

/** ISO 字符串（UTC），用于发给后端 */
export function toIso(d: Date | string | number): string {
  return new Date(d).toISOString()
}

export function toNumber(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  if (typeof v === 'boolean') return v ? 1 : 0
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v)
    return Number.isFinite(n) ? n : null
  }
  return null
}
