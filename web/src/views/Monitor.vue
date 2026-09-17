<template>
  <div class="page">
    <div v-if="error" class="alert error">{{ error }}</div>

    <div class="panel" style="margin-bottom: 14px">
      <div class="panel-head">
        <h3>数据质量报告</h3>
        <span class="hint">{{ quality.batchId ? '批次号 ' + quality.batchId : '暂无数据' }}</span>
      </div>
      <div class="panel-body">
        <div class="grid grid-kpi" style="margin-bottom: 16px">
          <div class="panel kpi-card">
            <div class="kpi-name">原始记录数</div>
            <div class="kpi-value">{{ formatNumber(quality.rawCnt) }}<span class="unit">条</span></div>
            <div class="kpi-desc">数据源读取到的全部记录</div>
          </div>
          <div class="panel kpi-card">
            <div class="kpi-name">清洗后有效记录</div>
            <div class="kpi-value">{{ formatNumber(quality.validCnt) }}<span class="unit">条</span></div>
            <div class="kpi-desc">进入 DWD 明细层的记录</div>
          </div>
          <div class="panel kpi-card">
            <div class="kpi-name">丢弃记录数</div>
            <div class="kpi-value">{{ formatNumber(quality.discardCnt) }}<span class="unit">条</span></div>
            <div class="kpi-desc">时间/数量/单价异常，已留痕可追溯</div>
          </div>
          <div class="panel kpi-card">
            <div class="kpi-name">数据质量得分</div>
            <div class="kpi-value">{{ ((quality.qualityScore || 0) * 100).toFixed(2) }}<span class="unit">%</span></div>
            <div class="kpi-desc">有效记录数 ÷ 原始记录数</div>
          </div>
          <div class="panel kpi-card">
            <div class="kpi-name">清洗耗时</div>
            <div class="kpi-value" style="font-size: 20px">{{ formatDuration(quality.durationMs) }}</div>
            <div class="kpi-desc">该批次数据清洗阶段耗时</div>
          </div>
        </div>

        <ChartBox :option="qualityOption" height="260px" />
      </div>
    </div>

    <div class="grid grid-2">
      <div class="panel">
        <div class="panel-head">
          <h3>清洗规则命中统计</h3>
          <span class="hint">规则之间可能有重叠，同一行可命中多条规则</span>
        </div>
        <div class="panel-body table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>规则</th>
                <th>说明</th>
                <th>处理方式</th>
                <th class="num">命中记录数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rules" :key="r.code">
                <td>{{ r.code }}</td>
                <td>{{ r.desc }}</td>
                <td>{{ r.action }}</td>
                <td class="num">{{ formatNumber(r.count) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <h3>计算方式性能对比</h3>
          <span class="hint">同一份数据分别用 RDD 与 DataFrame 实现</span>
        </div>
        <div class="panel-body table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>批次号</th>
                <th>计算方式</th>
                <th class="num">总耗时</th>
                <th class="num">输入行数</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in jobLogs" :key="row.id">
                <td>{{ row.batchId }}</td>
                <td>
                  <span class="rank-badge" :class="{ top: row.computeMode === 'DATAFRAME' }">
                    {{ row.computeMode }}
                  </span>
                </td>
                <td class="num">{{ formatDuration(row.durationMs) }}</td>
                <td class="num">{{ formatNumber(row.inputRows) }}</td>
                <td>{{ row.jobStatus }}</td>
              </tr>
              <tr v-if="!jobLogs.length">
                <td colspan="5" class="empty">暂无作业记录</td>
              </tr>
            </tbody>
          </table>
          <div v-if="performanceConclusion" class="alert info" style="margin-top: 12px; margin-bottom: 0">
            {{ performanceConclusion }}
          </div>
        </div>
      </div>
    </div>

    <div class="panel" style="margin-top: 14px">
      <div class="panel-head">
        <h3>数据一致性校验</h3>
        <span class="hint">ADS 预计算结果 vs DWD 明细实时汇总，用于验证指标口径一致性</span>
      </div>
      <div class="panel-body table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>校验项</th>
              <th class="num">预计算值</th>
              <th class="num">明细汇总值</th>
              <th class="num">差值</th>
              <th>单位</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in consistency" :key="row.item">
              <td>{{ row.item }}</td>
              <td class="num">{{ formatNumber(row.adsValue, 2) }}</td>
              <td class="num">{{ formatNumber(row.detailValue, 2) }}</td>
              <td class="num">{{ formatNumber(row.diff, 2) }}</td>
              <td>{{ row.unit }}</td>
              <td>
                <span class="tag" :style="row.passed ? 'color:#1d7a45;border-color:#bfe3cd' : 'color:#a52a22;border-color:#f5c2bd'">
                  {{ row.passed ? '通过' : '不一致' }}
                </span>
              </td>
            </tr>
            <tr v-if="!consistency.length">
              <td colspan="6" class="empty">暂无校验结果</td>
            </tr>
          </tbody>
        </table>
        <div class="alert info" style="margin-top: 12px; margin-bottom: 0">
          说明：用户数属于不可加指标，跨天求和会重复计数，因此不参与"预计算 vs 明细汇总"的逐项比对。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import ChartBox from '../components/ChartBox.vue'
import api from '../api'
import { formatDuration, formatNumber } from '../utils/format'
import { PALETTE, baseGrid, categoryAxis, valueAxis, tooltip, emptyOption } from '../utils/charts'

const error = ref('')
const quality = ref({})
const jobLogs = ref([])
const consistency = ref([])

const rules = computed(() => [
  { code: 'R01', desc: '省份 / 城市 / 支付方式为空', action: '填充为「未知」', count: quality.value.nullFieldCnt },
  { code: 'R02', desc: '完全重复记录', action: '去重，仅保留一条', count: quality.value.dupRecordCnt },
  { code: 'R03', desc: '下单时间无法解析', action: '丢弃并留痕', count: quality.value.invalidTimeCnt },
  { code: 'R04-R06', desc: '数量 / 单价 / 实付金额异常', action: '异常丢弃，金额不一致按公式重算', count: quality.value.invalidAmountCnt },
  { code: 'R07', desc: '订单状态非合法枚举', action: '标准化为「其他」', count: quality.value.invalidStatusCnt },
  { code: 'R08', desc: '商品主数据缺失', action: '类目填充为「未知类目」', count: quality.value.missingDimCnt }
])

const qualityOption = computed(() => {
  if (!quality.value.rawCnt) {
    return emptyOption()
  }
  const q = quality.value
  const items = [
    { name: '原始记录', value: Number(q.rawCnt || 0) },
    { name: '含空值', value: Number(q.nullFieldCnt || 0) },
    { name: '重复记录', value: Number(q.dupRecordCnt || 0) },
    { name: '时间异常', value: Number(q.invalidTimeCnt || 0) },
    { name: '金额异常', value: Number(q.invalidAmountCnt || 0) },
    { name: '状态非法', value: Number(q.invalidStatusCnt || 0) },
    { name: '维度缺失', value: Number(q.missingDimCnt || 0) },
    { name: '有效记录', value: Number(q.validCnt || 0) }
  ]
  return {
    color: PALETTE,
    grid: baseGrid,
    tooltip: tooltip(),
    xAxis: categoryAxis(items.map((i) => i.name), { axisLabel: { color: '#8b96a8', fontSize: 11, interval: 0 } }),
    yAxis: valueAxis(),
    series: [
      {
        type: 'bar',
        barMaxWidth: 34,
        itemStyle: { color: '#2f6fd0', borderRadius: [3, 3, 0, 0] },
        label: { show: true, position: 'top', fontSize: 10.5, color: '#5c6879', formatter: (p) => formatNumber(p.value) },
        data: items.map((i) => i.value)
      }
    ]
  }
})

const performanceConclusion = computed(() => {
  const df = jobLogs.value.find((j) => j.computeMode === 'DATAFRAME')
  const rdd = jobLogs.value.find((j) => j.computeMode === 'RDD')
  if (!df || !rdd) {
    return ''
  }
  const diff = rdd.durationMs - df.durationMs
  const pct = df.durationMs ? Math.abs((diff * 100) / df.durationMs).toFixed(1) : '0'
  const faster = diff > 0 ? 'DataFrame' : 'RDD'
  return `结论：DataFrame 方式耗时 ${formatDuration(df.durationMs)}，RDD 方式耗时 ${formatDuration(rdd.durationMs)}，`
    + `${faster} 更快，相差 ${formatDuration(Math.abs(diff))}（约 ${pct}%）。`
})

async function load() {
  error.value = ''
  try {
    const [q, logs, cons] = await Promise.all([
      api.quality(),
      api.jobLog(),
      api.consistency()
    ])
    quality.value = q || {}
    jobLogs.value = logs || []
    consistency.value = cons || []
  } catch (e) {
    error.value = e.message
  }
}

onMounted(load)
</script>
