<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="app-title">
        <h1>电商商品销售分析平台</h1>
        <span>基于 Spark 的离线分析与 Java 服务化应用</span>
      </div>
      <nav class="app-nav">
        <router-link to="/">销售分析看板</router-link>
        <router-link to="/monitor">运行监控</router-link>
      </nav>
    </header>
    <router-view />
  </div>
</template>

<script setup>
// 全局布局：顶部导航 + 路由出口
</script>
