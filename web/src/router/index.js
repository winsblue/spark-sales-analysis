import { createRouter, createWebHashHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Monitor from '../views/Monitor.vue'

const routes = [
  { path: '/', name: 'dashboard', component: Dashboard, meta: { title: '销售分析看板' } },
  { path: '/monitor', name: 'monitor', component: Monitor, meta: { title: '运行监控' } }
]

const router = createRouter({
  // 使用 hash 模式，部署时无需后端做 history 路由回退配置
  history: createWebHashHistory(),
  routes
})

export default router
