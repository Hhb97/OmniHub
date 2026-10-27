import { ElMessage } from 'element-plus'

export async function copyText(text: string, successMsg = '已复制到剪贴板'): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (!ok) throw new Error('copy failed')
    }
    ElMessage.success(successMsg)
    return true
  } catch {
    ElMessage.error('复制失败，请手动复制')
    return false
  }
}
