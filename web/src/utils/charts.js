/**
 * ECharts 公共配置：统一配色与坐标轴样式，避免每个图表各写一套。
 */

/** 分类配色（中国红为主色，依次为蓝、青、琥珀、紫……） */
export const PALETTE = [
  '#c8322b',
  '#2f6fd0',
  '#159c8e',
  '#d99a16',
  '#7058c9',
  '#e0763a',
  '#3f9ad6',
  '#8c6d3f',
  '#b4448e',
  '#5a7d8c'
]

export const TEXT_SUB = '#5c6879'
export const TEXT_MUTED = '#8b96a8'
export const SPLIT_LINE = '#eef1f6'

export const baseGrid = { left: 12, right: 18, top: 42, bottom: 8, containLabel: true }

/** 类目轴通用样式 */
export function categoryAxis(data, extra = {}) {
  return {
    type: 'category',
    data,
    axisLine: { lineStyle: { color: SPLIT_LINE } },
    axisTick: { show: false },
    axisLabel: { color: TEXT_MUTED, fontSize: 11, ...(extra.axisLabel || {}) },
    ...extra
  }
}

/** 数值轴通用样式 */
export function valueAxis(extra = {}) {
  return {
    type: 'value',
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: TEXT_MUTED, fontSize: 11, ...(extra.axisLabel || {}) },
    splitLine: { lineStyle: { color: SPLIT_LINE } },
    ...extra
  }
}

/** 统一的 tooltip 外观 */
export function tooltip(extra = {}) {
  return {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    backgroundColor: 'rgba(255,255,255,0.97)',
    borderColor: '#e4e8f0',
    borderWidth: 1,
    padding: [8, 12],
    textStyle: { color: '#1f2733', fontSize: 12 },
    ...extra
  }
}

/** 图例通用样式 */
export function legend(extra = {}) {
  return {
    top: 8,
    right: 8,
    itemWidth: 10,
    itemHeight: 10,
    textStyle: { color: TEXT_SUB, fontSize: 11.5 },
    ...extra
  }
}

/** 空数据占位提示 */
export function emptyOption(text = '暂无数据') {
  return {
    title: {
      text,
      left: 'center',
      top: 'middle',
      textStyle: { color: TEXT_MUTED, fontSize: 13, fontWeight: 'normal' }
    }
  }
}

/** 金额展示：自动切换万/亿单位 */
export function money(v) {
  const n = Number(v) || 0
  const abs = Math.abs(n)
  if (abs >= 100000000) {
    return `${(n / 100000000).toFixed(2)} 亿`
  }
  if (abs >= 10000) {
    return `${(n / 10000).toFixed(2)} 万`
  }
  return n.toFixed(2)
}
