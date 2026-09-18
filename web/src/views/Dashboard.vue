<template>
  <div class="page">
    <div v-if="error" class="alert error">{{ error }}</div>
    <div v-if="!loading && !hasData" class="alert info">
      未查询到数据。请先执行离线计算作业（spark-job 模块）生成指标结果，再刷新本页面。
    </div>

    <FilterBar
      v-model:value="filters"
      :meta="meta"
      :loading="loading"
      @search="loadAll"
      @reset="resetFilters"
    />

    <!-- 核心指标卡 -->
    <div class="grid grid-kpi" style="margin-bottom: 14px">
      <div v-for="kpi in overview" :key="kpi.code" class="panel kpi-card">
        <div class="kpi-name">
          {{ kpi.name }}
          <span class="tag">{{ kpi.source === 'PRECOMPUTE' ? '预计算' : '即席聚合' }}</span>
        </div>
        <div class="kpi-value">
          {{ formatKpi(kpi.code, kpi.value) }}
          <span class="unit">{{ kpi.unit }}</span>
        </div>
        <div class="kpi-desc">{{ kpi.desc }}</div>
      </div>
    </div>

    <!-- 趋势 + 渠道分布 -->
    <div class="grid grid-3" style="margin-bottom: 14px">
      <div class="panel">
        <div class="panel-head">
          <h3>销售趋势</h3>
          <span class="hint">柱状为 GMV，折线为有效订单量与下单用户数</span>
        </div>
        <div class="panel-body">
          <ChartBox :option="trendOption" height="330px" />
        </div>
      </div>
      <div class="panel">
        <div class="panel-head">
          <h3>渠道销售占比</h3>
        </div>
        <div class="panel-body">
          <ChartBox :option="channelOption" height="330px" />
        </div>
      </div>
    </div>

    <!-- 类目排行 + 支付方式 -->
    <div class="grid grid-2" style="margin-bottom: 14px">
      <div class="panel">
        <div class="panel-head">
          <h3>类目销售排行</h3>
          <span class="hint">按成交金额降序</span>
        </div>
        <div class="panel-body">
          <ChartBox :option="categoryOption" height="340px" />
        </div>
      </div>
      <div class="panel">
        <div class="panel-head">
          <h3>支付方式占比</h3>
        </div>
        <div class="panel-body">
          <ChartBox :option="paytypeOption" height="340px" />
        </div>
      </div>
    </div>

    <!-- 省份排行 + 渠道类目交叉对比 -->
    <div class="grid grid-2" style="margin-bottom: 14px">
      <div class="panel">
        <div class="panel-head">
          <h3>省份销售排行</h3>
          <span class="hint">Top {{ filters.limit }}</span>
        </div>
        <div class="panel-body">
          <ChartBox :option="regionOption" height="340px" />
        </div>
      </div>
      <div class="panel">
        <div class="panel-head">
          <h3>渠道 × 类目 交叉对比</h3>
          <span class="hint">分组对比：同一类目在不同渠道的成交金额</span>
        </div>
        <div class="panel-body">
          <ChartBox :option="channelCategoryOption" height="340px" />
        </div>
      </div>
    </div>

    <!-- 商品排行明细 -->
    <div class="panel" style="margin-bottom: 14px">
      <div class="panel-head">
        <h3>商品销售排行 Top {{ filters.limit }}</h3>
        <span class="hint">点击行可查看该商品的订单明细</span>
      </div>
      <div class="panel-body table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 60px">排名</th>
              <th>商品编号</th>
              <th>商品名称</th>
              <th>类目</th>
              <th>品牌</th>
              <th class="num">成交金额（BRL）</th>
              <th class="num">销售件数</th>
              <th class="num">订单量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in products" :key="row.productId">
              <td><span class="rank-badge" :class="{ top: row.rankNo <= 3 }">{{ row.rankNo }}</span></td>
              <td>{{ row.productId }}</td>
              <td>{{ row.productName }}</td>
              <td>{{ row.categoryName }}</td>
              <td>{{ row.brand }}</td>
              <td class="num">{{ formatMoney(row.gmv) }}</td>
              <td class="num">{{ formatNumber(row.salesQty) }}</td>
              <td class="num">{{ formatNumber(row.orderCnt) }}</td>
            </tr>
            <tr v-if="!products.length">
              <td colspan="8" class="empty">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 明细下钻 -->
    <div class="panel">
      <div class="panel-head">
        <h3>订单明细下钻</h3>
        <div style="display: flex; align-items: center; gap: 12px">
          <span class="hint">共 {{ detail.total }} 条</span>
          <button class="btn ghost" @click="exportCsv">导出 CSV</button>
        </div>
      </div>
      <div class="panel-body table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>订单编号</th>
              <th>下单时间</th>
              <th>商品名称</th>
              <th>类目</th>
              <th>渠道</th>
              <th>省份</th>
              <th class="num">数量</th>
              <th class="num">实付金额（BRL）</th>
              <th>订单状态</th>
              <th>支付方式</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in detail.records" :key="row.id">
              <td>{{ row.orderId }}</td>
              <td>{{ row.orderTime }}</td>
              <td>{{ row.productName }}</td>
              <td>{{ row.categoryName }}</td>
              <td>{{ row.channel }}</td>
              <td>{{ row.province }}</td>
              <td class="num">{{ row.quantity }}</td>
              <td class="num">{{ formatMoney(row.payAmount) }}</td>
              <td>{{ row.orderStatus }}</td>
              <td>{{ row.payType }}</td>
            </tr>
            <tr v-if="!detail.records.length">
              <td colspan="10" class="empty">暂无数据</td>
            </tr>
          </tbody>
        </table>

        <div class="pager">
          <span>第 {{ detail.pageNum }} / {{ detail.pages || 1 }} 页</span>
          <select :value="String(pageSize)" @change="changePageSize(Number($event.target.value))">
            <option v-for="n in [10, 20, 50]" :key="n" :value="String(n)">{{ n }} 条/页</option>
          </select>
          <button :disabled="detail.pageNum <= 1" @click="changePage(detail.pageNum - 1)">上一页</button>
          <button :disabled="detail.pageNum >= detail.pages" @click="changePage(detail.pageNum + 1)">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import FilterBar from '../components/FilterBar.vue'
import ChartBox from '../components/ChartBox.vue'
import api from '../api'
import { formatKpi, formatMoney, formatNumber } from '../utils/format'
import {
  PALETTE, baseGrid, categoryAxis, valueAxis, tooltip, legend, emptyOption, money
} from '../utils/charts'

const error = ref('')
const loading = ref(false)
const meta = reactive({})
const pageSize = ref(10)

const DEFAULT_FILTERS = {
  startDate: '',
  endDate: '',
  channel: '',
  categoryName: '',
  province: '',
  limit: 10
}

const filters = ref({ ...DEFAULT_FILTERS })

const overview = ref([])
const trend = ref([])
const categories = ref([])
const products = ref([])
const channels = ref([])
const paytypes = ref([])
const regions = ref([])
const channelCategory = ref([])
const detail = reactive({ pageNum: 1, pageSize: 10, total: 0, pages: 0, records: [] })

const hasData = computed(() => overview.value.length > 0 || trend.value.length > 0)

/* ---------------- 数据加载 ---------------- */

async function loadMeta() {
  try {
    const data = await api.filters()
    Object.assign(meta, data || {})
    if (!filters.value.startDate && meta.minDate) {
      filters.value = { ...filters.value, startDate: meta.minDate, endDate: meta.maxDate }
    }
  } catch (e) {
    error.value = e.message
  }
}

async function loadAll() {
  loading.value = true
  error.value = ''
  const q = { ...filters.value }
  try {
    const [kpi, tr, cat, prod, ch, pay, reg, chCat] = await Promise.all([
      api.overview(q),
      api.trend(q),
      api.categoryTopN(q),
      api.productTopN(q),
      api.channelDist(q),
      api.paytypeDist(q),
      api.regionTopN(q),
      api.channelCategory(q)
    ])
    overview.value = kpi || []
    trend.value = tr || []
    categories.value = cat || []
    products.value = prod || []
    channels.value = ch || []
    paytypes.value = pay || []
    regions.value = reg || []
    channelCategory.value = chCat || []
    await loadDetail(1)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadDetail(pageNum) {
  try {
    const data = await api.orderPage({
      ...filters.value,
      pageNum,
      pageSize: pageSize.value
    })
    detail.pageNum = data.pageNum
    detail.pageSize = data.pageSize
    detail.total = data.total
    detail.pages = data.pages
    detail.records = data.records || []
  } catch (e) {
    error.value = e.message
  }
}

function resetFilters() {
  filters.value = {
    ...DEFAULT_FILTERS,
    startDate: meta.minDate || '',
    endDate: meta.maxDate || ''
  }
  loadAll()
}

function changePage(p) {
  loadDetail(p)
}

function changePageSize(size) {
  pageSize.value = size
  loadDetail(1)
}

function exportCsv() {
  window.open(api.orderExportUrl(filters.value), '_blank')
}

onMounted(async () => {
  await loadMeta()
  await loadAll()
})

/* ---------------- 图表配置 ---------------- */

const trendOption = computed(() => {
  if (!trend.value.length) {
    return emptyOption()
  }
  return {
    color: PALETTE,
    grid: { ...baseGrid, top: 48 },
    tooltip: tooltip({
      valueFormatter: (v) => (typeof v === 'number' ? v.toLocaleString('zh-CN') : v)
    }),
    legend: legend(),
    xAxis: categoryAxis(trend.value.map((d) => d.statDate)),
    yAxis: [
      valueAxis({ name: 'GMV（BRL）', nameTextStyle: { color: '#8b96a8', fontSize: 11 }, axisLabel: { formatter: (v) => money(v) } }),
      valueAxis({ name: '单量 / 人数', nameTextStyle: { color: '#8b96a8', fontSize: 11 }, splitLine: { show: false } })
    ],
    series: [
      {
        name: 'GMV',
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: 'rgba(200,50,43,0.85)', borderRadius: [2, 2, 0, 0] },
        data: trend.value.map((d) => Number(d.gmv))
      },
      {
        name: '有效订单量',
        type: 'line',
        smooth: true,
        symbolSize: 5,
        lineStyle: { width: 2, color: '#2f6fd0' },
        itemStyle: { color: '#2f6fd0' },
        yAxisIndex: 1,
        data: trend.value.map((d) => Number(d.validOrderCnt))
      },
      {
        name: '下单用户数',
        type: 'line',
        smooth: true,
        symbolSize: 5,
        lineStyle: { width: 2, color: '#159c8e' },
        itemStyle: { color: '#159c8e' },
        yAxisIndex: 1,
        data: trend.value.map((d) => Number(d.buyerCnt))
      }
    ]
  }
})

const channelOption = computed(() => {
  if (!channels.value.length) {
    return emptyOption()
  }
  return {
    color: PALETTE,
    tooltip: tooltip({
      trigger: 'item',
      formatter: (p) => `${p.name}<br/>成交金额：${money(p.value)} BRL<br/>占比：${p.percent}%`
    }),
    legend: legend({ orient: 'horizontal', top: 'auto', bottom: 0, left: 'center' }),
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{d}%', fontSize: 11, color: '#5c6879' },
        data: channels.value.map((c) => ({ name: c.name, value: Number(c.gmv) }))
      }
    ]
  }
})

const categoryOption = computed(() => {
  if (!categories.value.length) {
    return emptyOption()
  }
  const rows = [...categories.value].reverse()
  return {
    color: PALETTE,
    grid: { left: 12, right: 60, top: 16, bottom: 8, containLabel: true },
    tooltip: tooltip({
      formatter: (p) => {
        const d = p[0]
        return `${d.name}<br/>成交金额：${money(d.value)} BRL<br/>占比：${rows[d.dataIndex].ratio}%`
      }
    }),
    xAxis: valueAxis({ axisLabel: { formatter: (v) => money(v) } }),
    yAxis: categoryAxis(rows.map((c) => c.name)),
    series: [
      {
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: '#c8322b', borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', fontSize: 11, color: '#5c6879', formatter: (p) => money(p.value) },
        data: rows.map((c) => Number(c.gmv))
      }
    ]
  }
})

const paytypeOption = computed(() => {
  if (!paytypes.value.length) {
    return emptyOption()
  }
  return {
    color: PALETTE.slice(1),
    tooltip: tooltip({
      trigger: 'item',
      formatter: (p) => `${p.name}<br/>成交金额：${money(p.value)} BRL<br/>占比：${p.percent}%`
    }),
    legend: legend({ orient: 'horizontal', bottom: 0, top: 'auto', left: 'center' }),
    series: [
      {
        type: 'pie',
        radius: '62%',
        center: ['50%', '46%'],
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b} {d}%', fontSize: 11, color: '#5c6879' },
        data: paytypes.value.map((c) => ({ name: c.name, value: Number(c.gmv) }))
      }
    ]
  }
})

const regionOption = computed(() => {
  if (!regions.value.length) {
    return emptyOption()
  }
  const rows = [...regions.value].reverse()
  return {
    grid: { left: 12, right: 20, top: 30, bottom: 8, containLabel: true },
    tooltip: tooltip({
      formatter: (p) => {
        const d = p[0]
        return `${d.name}<br/>成交金额：${money(d.value)} BRL<br/>占比：${rows[d.dataIndex].ratio}%`
      }
    }),
    xAxis: categoryAxis(rows.map((r) => r.name), { axisLabel: { color: '#8b96a8', fontSize: 10.5, interval: 0, rotate: 40 } }),
    yAxis: valueAxis({ axisLabel: { formatter: (v) => money(v) } }),
    series: [
      {
        type: 'bar',
        barMaxWidth: 20,
        itemStyle: {
          color: '#2f6fd0',
          borderRadius: [3, 3, 0, 0]
        },
        data: rows.map((r) => Number(r.gmv))
      }
    ]
  }
})

const channelCategoryOption = computed(() => {
  const rows = channelCategory.value
  if (!rows.length) {
    return emptyOption()
  }
  // 取成交金额最高的前若干类目，避免横轴过长
  const catTotal = {}
  rows.forEach((r) => {
    catTotal[r.categoryName] = (catTotal[r.categoryName] || 0) + Number(r.gmv)
  })
  const topCats = Object.keys(catTotal)
    .sort((a, b) => catTotal[b] - catTotal[a])
    .slice(0, 6)
  const channelNames = [...new Set(rows.map((r) => r.channel))]

  const series = channelNames.map((ch, idx) => ({
    name: ch,
    type: 'bar',
    barMaxWidth: 20,
    itemStyle: { color: PALETTE[idx % PALETTE.length], borderRadius: [2, 2, 0, 0] },
    data: topCats.map((cat) => {
      const hit = rows.find((r) => r.channel === ch && r.categoryName === cat)
      return hit ? Number(hit.gmv) : 0
    })
  }))

  return {
    grid: { ...baseGrid, top: 48 },
    tooltip: tooltip({ formatter: (ps) => {
      const title = ps[0].axisValue
      const lines = ps
        .filter((p) => Number(p.value) > 0)
        .map((p) => `${p.marker}${p.seriesName}：${money(p.value)} BRL`)
      return [title, ...lines].join('<br/>')
    } }),
    legend: legend({ top: 8 }),
    xAxis: categoryAxis(topCats, { axisLabel: { color: '#8b96a8', fontSize: 11, interval: 0 } }),
    yAxis: valueAxis({ axisLabel: { formatter: (v) => money(v) } }),
    series
  }
})
</script>
