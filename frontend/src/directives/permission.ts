import type { App, Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/** v-permission="'product:create'" 或 v-permission="['a','b']"（满足任一即显示） */
const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    if (!auth.hasPermission(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}

export function setupDirectives(app: App): void {
  app.directive('permission', permission)
}
