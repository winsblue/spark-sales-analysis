/**
 * 数值格式化工具。
 */

/** 千分位数字，可指定小数位 */
export function formatNumber(value, digits = 0) {
  const num = Number(value)
  if (!isFinite(num)) {
    return '0'
  }
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  })
}

/** 金额（元），默认保留 2 位小数 */
export function formatMoney(value) {
  return formatNumber(value, 2)
}

/** 大额金额自动转换为「万 / 亿」，用于图表轴标签与指标卡 */
export function formatCompact(value) {
  const num = Number(value)
  if (!isFinite(num)) {
    return '0'
  }
  const abs = Math.abs(num)
  if (abs >= 100000000) {
    return `${(num / 100000000).toFixed(2)}亿`
  }
  if (abs >= 10000) {
    return `${(num / 10000).toFixed(2)}万`
  }
  return formatNumber(num, 2)
}

/** 百分比展示 */
export function formatPercent(value, digits = 2) {
  const num = Number(value)
  if (!isFinite(num)) {
    return '0%'
  }
  return `${num.toFixed(digits)}%`
}

/** 耗时毫秒转可读文本 */
export function formatDuration(ms) {
  const num = Number(ms)
  if (!isFinite(num) || num <= 0) {
    return '-'
  }
  if (num < 1000) {
    return `${num} ms`
  }
  if (num < 60000) {
    return `${(num / 1000).toFixed(2)} s`
  }
  return `${(num / 60000).toFixed(2)} min`
}

/** 按指标编码决定数值展示方式 */
export function formatKpi(code, value) {
  if (code === 'GMV' || code === 'AVG_ORDER_AMOUNT' || code === 'AVG_ITEM_PRICE') {
    return formatCompact(value)
  }
  if (code === 'REFUND_RATE' || code === 'DISCOUNT_RATE') {
    return formatPercent(value)
  }
  return formatNumber(value)
}
