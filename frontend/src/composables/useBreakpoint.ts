import { onBeforeUnmount, onMounted, ref } from 'vue'

/** 窗口宽度小于 breakpoint 视为窄屏 */
export function useBreakpoint(breakpoint = 992) {
  const isNarrow = ref(window.innerWidth < breakpoint)
  const onResize = () => {
    isNarrow.value = window.innerWidth < breakpoint
  }
  onMounted(() => window.addEventListener('resize', onResize))
  onBeforeUnmount(() => window.removeEventListener('resize', onResize))
  return { isNarrow }
}
