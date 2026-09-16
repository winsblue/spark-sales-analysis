<template>
  <div class="panel filter-bar">
    <div class="filter-item">
      <label>开始日期</label>
      <input type="date" :value="value.startDate" :min="meta.minDate" :max="meta.maxDate"
             @change="update('startDate', $event.target.value)" />
    </div>
    <div class="filter-item">
      <label>结束日期</label>
      <input type="date" :value="value.endDate" :min="meta.minDate" :max="meta.maxDate"
             @change="update('endDate', $event.target.value)" />
    </div>
    <div class="filter-item">
      <label>销售渠道</label>
      <select :value="value.channel" @change="update('channel', $event.target.value)">
        <option value="">全部渠道</option>
        <option v-for="c in meta.channels" :key="c" :value="c">{{ c }}</option>
      </select>
    </div>
    <div class="filter-item">
      <label>商品类目</label>
      <select :value="value.categoryName" @change="update('categoryName', $event.target.value)">
        <option value="">全部类目</option>
        <option v-for="c in meta.categories" :key="c" :value="c">{{ c }}</option>
      </select>
    </div>
    <div class="filter-item">
      <label>收货省份</label>
      <select :value="value.province" @change="update('province', $event.target.value)">
        <option value="">全部省份</option>
        <option v-for="c in meta.provinces" :key="c" :value="c">{{ c }}</option>
      </select>
    </div>
    <div class="filter-item">
      <label>排行条数</label>
      <select :value="String(value.limit)" @change="update('limit', Number($event.target.value))">
        <option v-for="n in [5, 10, 15, 20]" :key="n" :value="String(n)">Top {{ n }}</option>
      </select>
    </div>
    <div class="filter-item">
      <label>&nbsp;</label>
      <div style="display: flex; gap: 8px">
        <button class="btn" :disabled="loading" @click="$emit('search')">
          {{ loading ? '查询中…' : '查询' }}
        </button>
        <button class="btn ghost" :disabled="loading" @click="$emit('reset')">重置</button>
      </div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  value: { type: Object, required: true },
  meta: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:value', 'search', 'reset'])

function update(key, v) {
  emit('update:value', { ...props.value, [key]: v })
}
</script>
