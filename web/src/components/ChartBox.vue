<template>
  <div ref="el" class="chart-box" :style="{ height }"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, nextTick, ref, watch } from 'vue'

/**
 * ECharts 通用容器组件。
 * 外部只需传入 option，组件负责初始化、响应式更新与窗口自适应。
 */
const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' }
})

const el = ref(null)
let chart = null

function render() {
  if (!el.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(el.value)
  }
  chart.setOption(props.option, true)
  chart.resize()
}

function onResize() {
  if (chart) {
    chart.resize()
  }
}

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(
  () => props.option,
  async () => {
    await nextTick()
    render()
  },
  { deep: true }
)
</script>
